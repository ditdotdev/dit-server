/*
 * Copyright Dit.
 */
package remote

import (
	"context"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	dit "github.com/ditdotdev/dit-client-go"
	endtoend "github.com/ditdotdev/dit-server/test/common"
	"github.com/stretchr/testify/suite"
	"os"
	"strings"
	"testing"
)

type S3TestSuite struct {
	suite.Suite
	e   *endtoend.EndToEndTest
	ctx context.Context

	s3bucket     string
	s3path       string
	remote       dit.Remote
	remoteParams dit.RemoteParameters

	// clearBucketFn lets unit tests substitute the bucket-clearing call. In
	// production it is wired to ClearBucket during SetupSuite. See issue #156.
	clearBucketFn func() error
}

func (s *S3TestSuite) ClearBucket() error {
	cfg, err := config.LoadDefaultConfig(context.Background())
	if err != nil {
		return err
	}
	svc := s3.NewFromConfig(cfg)
	res, err := svc.ListObjects(context.Background(), &s3.ListObjectsInput{Bucket: aws.String(s.s3bucket), Prefix: aws.String(s.s3path)})
	if err != nil {
		return err
	}
	for _, obj := range res.Contents {
		_, err = svc.DeleteObject(context.Background(), &s3.DeleteObjectInput{
			Bucket: aws.String(s.s3bucket),
			Key:    obj.Key,
		})
		if err != nil {
			return err
		}
	}
	return nil
}

// splitS3Location parses an S3_LOCATION value into its bucket and path.
// It tolerates an optional scheme (e.g. "s3://") on the location: the
// S3_TEST_LOCATION secret was migrated to an `s3://dit-*` URI form
// (ditdotdev/dit#165), but the S3 / S3Web suites need the bare bucket name to
// call the S3 API directly. Panics if no '/' separates bucket from path.
func splitS3Location(location string) (bucket, path string) {
	if i := strings.Index(location, "://"); i >= 0 {
		location = location[i+len("://"):]
	}
	idx := strings.IndexByte(location, '/')
	if idx < 0 {
		panic("S3_LOCATION must be in the format 'bucket/path', got: " + location)
	}
	return location[:idx], location[idx+1:]
}

func TestSplitS3Location(t *testing.T) {
	cases := []struct {
		in, bucket, path string
	}{
		{"s3://dit-testdata/run-1-1", "dit-testdata", "run-1-1"}, // s3:// scheme (post ditdotdev/dit#165)
		{"dit-testdata/run-1-1", "dit-testdata", "run-1-1"},      // bare bucket/path
		{"s3://dit-testdata/sub/run-1", "dit-testdata", "sub/run-1"},
	}
	for _, c := range cases {
		b, p := splitS3Location(c.in)
		if b != c.bucket || p != c.path {
			t.Errorf("splitS3Location(%q) = (%q, %q), want (%q, %q)", c.in, b, p, c.bucket, c.path)
		}
	}
}

func TestSplitS3LocationPanicsWithoutSlash(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Error("expected panic for location without '/'")
		}
	}()
	splitS3Location("nopath")
}

func (s *S3TestSuite) SetupSuite() {
	location := os.Getenv("S3_LOCATION")
	if location == "" {
		panic("S3_LOCATION must be set in environment")
	}
	s.s3bucket, s.s3path = splitS3Location(location)
	if s.clearBucketFn == nil {
		s.clearBucketFn = s.ClearBucket
	}
	err := s.clearBucketFn()
	if err != nil {
		panic(err)
	}

	cfg, err := config.LoadDefaultConfig(context.Background())
	if err != nil {
		panic(err)
	}
	creds, err := cfg.Credentials.Retrieve(context.Background())
	if err != nil {
		panic(err)
	}

	s.remote = dit.Remote{
		Provider: "s3",
		Name:     "origin",
		Properties: map[string]interface{}{
			"bucket":    s.s3bucket,
			"path":      s.s3path,
			"accessKey": creds.AccessKeyID,
			"secretKey": creds.SecretAccessKey,
			"region":    cfg.Region,
		},
	}

	s.e = endtoend.NewEndToEndTest(&s.Suite, "docker-zfs")
	s.e.SetupStandardDocker()

	s.ctx = context.Background()

	s.remoteParams = dit.RemoteParameters{
		Provider:   "s3",
		Properties: map[string]interface{}{},
	}
}

func (s *S3TestSuite) TearDownSuite() {
	if s.clearBucketFn != nil {
		// Clean up the per-run S3 prefix so it does not accumulate across CI
		// runs. See issue #156.
		if err := s.clearBucketFn(); err != nil {
			s.T().Logf("S3TestSuite TearDownSuite ClearBucket failed: %v", err)
		}
	}
	if s.e != nil {
		s.e.TeardownStandardDocker()
	}
}

func TestS3TestSuite(t *testing.T) {
	suite.Run(t, new(S3TestSuite))
}

func (s *S3TestSuite) TestS3_001_CreateRepository() {
	_, _, err := s.e.RepoApi.CreateRepository(s.ctx).Repository(dit.Repository{
		Name:       "foo",
		Properties: map[string]interface{}{},
	}).Execute()
	s.e.NoError(err)
}

func (s *S3TestSuite) TestS3_002_CreateMountVolume() {
	_, _, err := s.e.VolumeApi.CreateVolume(s.ctx, "foo").Volume(dit.Volume{
		Name:       "vol",
		Properties: map[string]interface{}{},
	}).Execute()
	if s.e.NoError(err) {
		_, err := s.e.VolumeApi.ActivateVolume(s.ctx, "foo", "vol").Execute()
		s.e.NoError(err)
	}
}

func (s *S3TestSuite) TestS3_003_CreateFile() {
	err := s.e.WriteFile("foo", "vol", "testfile", "Hello")
	if s.e.NoError(err) {
		res, err := s.e.ReadFile("foo", "vol", "testfile")
		if s.e.NoError(err) {
			s.Equal("Hello", res)
		}
	}
}

func (s *S3TestSuite) TestS3_004_CreateCommit() {
	res, _, err := s.e.CommitApi.CreateCommit(s.ctx, "foo").Commit(dit.Commit{
		Id: "id",
		Properties: map[string]interface{}{"tags": map[string]string{
			"a": "b",
			"c": "d",
		}},
	}).Execute()
	if s.e.NoError(err) {
		s.Equal("id", res.Id)
		s.Equal("b", s.e.GetTag(*res, "a"))
	}
}

func (s *S3TestSuite) TestS3_005_AddRemote() {
	res, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo").Remote(s.remote).Execute()
	if s.e.NoError(err) {
		s.Equal("origin", res.Name)
		s.Equal(s.s3bucket, res.Properties["bucket"])
		s.Equal(s.s3path, res.Properties["path"])
	}
}

func (s *S3TestSuite) TestS3_010_ListEmptyRemoteCommits() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *S3TestSuite) TestS3_011_GetBadRemoteCommit() {
	_, _, err := s.e.RemoteApi.GetRemoteCommit(s.ctx, "foo", "origin", "id2").RemoteParameters(s.remoteParams).Execute()
	s.e.APIError(err, "NoSuchObjectException")
}

func (s *S3TestSuite) TestS3_020_PushCommit() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3TestSuite) TestS3_021_ListRemoteCommit() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
		s.Equal("b", s.e.GetTag(res[0], "a"))
	}
}

func (s *S3TestSuite) TestS3_022_ListRemoteFilterOut() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
		RemoteParameters(s.remoteParams).Tag([]string{"e"}).Execute()
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *S3TestSuite) TestS3_023_ListRemoteFilterInclude() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
		RemoteParameters(s.remoteParams).Tag([]string{"a=b", "c=d"}).Execute()
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
	}
}

func (s *S3TestSuite) TestS3_030_PushDuplicateCommit() {
	_, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	s.e.APIError(err, "ObjectExistsException")
}

func (s *S3TestSuite) TestS3_031_UpdateCommit() {
	res, _, err := s.e.CommitApi.UpdateCommit(s.ctx, "foo", "id").Commit(dit.Commit{
		Id: "id",
		Properties: map[string]interface{}{"tags": map[string]string{
			"a": "B",
			"c": "e",
		}},
	}).Execute()
	if s.e.NoError(err) {
		s.Equal("B", s.e.GetTag(*res, "a"))
	}
}

func (s *S3TestSuite) TestS3_032_PushMedata() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id").
		RemoteParameters(s.remoteParams).MetadataOnly(true).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3TestSuite) TestS3_033_RemoteMetadataUpdated() {
	res, _, err := s.e.RemoteApi.GetRemoteCommit(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		s.Equal("id", res.Id)
		s.Equal("B", s.e.GetTag(*res, "a"))
	}
}

func (s *S3TestSuite) TestS3_040_DeleteLocalCommit() {
	_, err := s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id").Execute()
	s.e.NoError(err)
}

func (s *S3TestSuite) TestS3_041_ListEmptyCommits() {
	res, _, err := s.e.CommitApi.ListCommits(s.ctx, "foo").Execute()
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *S3TestSuite) TestS3_042_UpdateFile() {
	err := s.e.WriteFile("foo", "vol", "testfile", "Goodbye")
	if s.e.NoError(err) {
		res, err := s.e.ReadFile("foo", "vol", "testfile")
		if s.e.NoError(err) {
			s.Equal("Goodbye", res)
		}
	}
}

func (s *S3TestSuite) TestS3_043_PullCommit() {
	res, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3TestSuite) TestS3_044_PullDuplicate() {
	_, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	s.e.APIError(err, "ObjectExistsException")
}

func (s *S3TestSuite) TestS3_045_PullMetadata() {
	res, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").
		RemoteParameters(s.remoteParams).MetadataOnly(true).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3TestSuite) TestS3_046_CheckoutCommit() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		_, err := s.e.CommitApi.CheckoutCommit(s.ctx, "foo", "id").Execute()
		if s.e.NoError(err) {
			_, err = s.e.VolumeApi.ActivateVolume(s.ctx, "foo", "vol").Execute()
			s.e.NoError(err)
		}
	}
}

func (s *S3TestSuite) TestS3_047_OriginalContents() {
	res, err := s.e.ReadFile("foo", "vol", "testfile")
	if s.e.NoError(err) {
		s.Equal("Hello", res)
	}
}

func (s *S3TestSuite) TestS3_050_RemoveRemote() {
	_, err := s.e.RemoteApi.DeleteRemote(s.ctx, "foo", "origin").Execute()
	s.e.NoError(err)
}

func (s *S3TestSuite) TestS3_051_AddRemoteNoKeys() {
	_, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo").Remote(dit.Remote{
		Provider: "s3",
		Name:     "origin",
		Properties: map[string]interface{}{
			"bucket": s.s3bucket,
			"path":   s.s3path,
		},
	}).Execute()
	s.e.NoError(err)
}

func (s *S3TestSuite) TestS3_052_ListCommitsKeys() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
		RemoteParameters(dit.RemoteParameters{
			Provider: "s3",
			Properties: map[string]interface{}{
				"accessKey": s.remote.Properties["accessKey"],
				"secretKey": s.remote.Properties["secretKey"],
				"region":    s.remote.Properties["region"],
			},
		}).Execute()
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
	}
}

func (s *S3TestSuite) TestS3_053_ListCommitsNoKeys() {
	_, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").RemoteParameters(s.remoteParams).Execute()
	s.e.APIError(err, "IllegalArgumentException")
}

func (s *S3TestSuite) TestS3_054_ListCommitsIncorrectKeys() {
	_, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
		RemoteParameters(dit.RemoteParameters{
			Provider: "s3",
			Properties: map[string]interface{}{
				"accessKey": "ACCESS",
				"secretKey": "SECRET",
				"region":    s.remote.Properties["region"],
			},
		}).Execute()
	s.e.APIError(err, "S3Exception")
}

func (s *S3TestSuite) TestS3_055_PullKeys() {
	_, err := s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id").Execute()
	if s.e.NoError(err) {
		res, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").
			RemoteParameters(dit.RemoteParameters{
				Provider: "s3",
				Properties: map[string]interface{}{
					"accessKey": s.remote.Properties["accessKey"],
					"secretKey": s.remote.Properties["secretKey"],
					"region":    s.remote.Properties["region"],
				},
			}).Execute()
		if s.e.NoError(err) {
			_, err = s.e.WaitForOperation(res.Id)
			s.e.NoError(err)
		}
	}
}

func (s *S3TestSuite) TestS3_070_DeleteVolume() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		_, err = s.e.VolumeApi.DeleteVolume(s.ctx, "foo", "vol").Execute()
		s.e.NoError(err)
	}
}

func (s *S3TestSuite) TestS3_071_DeleteRepository() {
	_, err := s.e.RepoApi.DeleteRepository(s.ctx, "foo").Execute()
	s.e.NoError(err)
}
