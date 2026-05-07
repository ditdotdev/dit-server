/*
 * Copyright Datadatdat.
 */
package remote

import (
	"context"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/antihax/optional"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	datadatdat "github.com/datadatdat/datadatdat-client-go"
	endtoend "github.com/datadatdat/datadatdat-server/test/common"
	"github.com/stretchr/testify/suite"
)

type S3WebTestSuite struct {
	suite.Suite
	e   *endtoend.EndToEndTest
	ctx context.Context

	s3bucket      string
	s3path        string
	s3remote      datadatdat.Remote
	webRemote     datadatdat.Remote
	s3parameters  datadatdat.RemoteParameters
	webParameters datadatdat.RemoteParameters

	// clearBucketFn lets unit tests substitute the bucket-clearing call. In
	// production it is wired to ClearBucket during SetupSuite. See issue #156.
	clearBucketFn func() error
}

func (s *S3WebTestSuite) ClearBucket() error {
	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		return err
	}
	svc := s3.NewFromConfig(cfg)
	res, err := svc.ListObjects(context.TODO(), &s3.ListObjectsInput{Bucket: aws.String(s.s3bucket), Prefix: aws.String(s.s3path)})
	if err != nil {
		return err
	}
	for _, obj := range res.Contents {
		_, err = svc.DeleteObject(context.TODO(), &s3.DeleteObjectInput{
			Bucket: aws.String(s.s3bucket),
			Key:    obj.Key,
		})
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *S3WebTestSuite) SetupSuite() {
	location := os.Getenv("S3_LOCATION")
	if location == "" {
		panic("S3_LOCATION must be set in environment")
	}
	idx := strings.IndexByte(location, '/')
	if idx < 0 {
		panic("S3_LOCATION must be in the format 'bucket/path', got: " + location)
	}
	s.s3bucket = location[:idx]
	s.s3path = location[idx+1:]
	if s.clearBucketFn == nil {
		s.clearBucketFn = s.ClearBucket
	}
	err := s.clearBucketFn()
	if err != nil {
		panic(err)
	}

	cfg, err := config.LoadDefaultConfig(context.TODO())
	if err != nil {
		panic(err)
	}
	creds, err := cfg.Credentials.Retrieve(context.TODO())
	if err != nil {
		panic(err)
	}

	s.s3remote = datadatdat.Remote{
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
	s.webRemote = datadatdat.Remote{
		Provider: "s3web",
		Name:     "web",
		Properties: map[string]interface{}{
			"url": fmt.Sprintf("http://%s.s3.amazonaws.com/%s", s.s3bucket, s.s3path),
		},
	}

	s.e = endtoend.NewEndToEndTest(&s.Suite, "docker-zfs")
	s.e.SetupStandardDocker()

	s.ctx = context.Background()

	s.s3parameters = datadatdat.RemoteParameters{
		Provider:   "s3",
		Properties: map[string]interface{}{},
	}

	s.webParameters = datadatdat.RemoteParameters{
		Provider:   "s3web",
		Properties: map[string]interface{}{},
	}
}

func (s *S3WebTestSuite) TearDownSuite() {
	if s.clearBucketFn != nil {
		// Clean up the per-run S3 prefix so it does not accumulate across CI
		// runs. See issue #156.
		if err := s.clearBucketFn(); err != nil {
			s.T().Logf("S3WebTestSuite TearDownSuite ClearBucket failed: %v", err)
		}
	}
	if s.e != nil {
		s.e.TeardownStandardDocker()
	}
}

func TestS3WebTestSuite(t *testing.T) {
	suite.Run(t, new(S3WebTestSuite))
}

func (s *S3WebTestSuite) TestS3Web_001_CreateRepository() {
	_, _, err := s.e.RepoApi.CreateRepository(s.ctx, datadatdat.Repository{
		Name:       "foo",
		Properties: map[string]interface{}{},
	})
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_002_CreateMountVolume() {
	_, _, err := s.e.VolumeApi.CreateVolume(s.ctx, "foo", datadatdat.Volume{
		Name:       "vol",
		Properties: map[string]interface{}{},
	})
	if s.e.NoError(err) {
		_, err := s.e.VolumeApi.ActivateVolume(s.ctx, "foo", "vol")
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_003_CreateFile() {
	err := s.e.WriteFile("foo", "vol", "testfile", "Hello")
	if s.e.NoError(err) {
		res, err := s.e.ReadFile("foo", "vol", "testfile")
		if s.e.NoError(err) {
			s.Equal("Hello", res)
		}
	}
}

func (s *S3WebTestSuite) TestS3Web_004_CreateCommit() {
	res, _, err := s.e.CommitApi.CreateCommit(s.ctx, "foo", datadatdat.Commit{
		Id: "id",
		Properties: map[string]interface{}{"tags": map[string]string{
			"a": "b",
			"c": "d",
		}},
	})
	if s.e.NoError(err) {
		s.Equal("id", res.Id)
		s.Equal("b", s.e.GetTag(res, "a"))
	}
}

func (s *S3WebTestSuite) TestS3Web_005_AddS3Remote() {
	_, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo", s.s3remote)
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_006_AddS3WebRemote() {
	_, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo", s.webRemote)
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_010_ListEmptyRemoteCommits() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web", s.webParameters, nil)
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *S3WebTestSuite) TestS3Web_011_GetBadRemoteCommit() {
	_, _, err := s.e.RemoteApi.GetRemoteCommit(s.ctx, "foo", "web", "id2", s.webParameters)
	s.e.APIError(err, "NoSuchObjectException")
}

func (s *S3WebTestSuite) TestS3Web_020_PushCommit() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id", s.s3parameters, nil)
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_021_ListRemoteCommit() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web", s.webParameters, nil)
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
		s.Equal("b", s.e.GetTag(res[0], "a"))
	}
}

func (s *S3WebTestSuite) TestS3Web_022_ListRemoteFilterOut() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web", s.webParameters,
		&datadatdat.ListRemoteCommitsOpts{Tag: optional.NewInterface([]string{"e"})})
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *S3WebTestSuite) TestS3Web_023_ListRemoteFilterInclude() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web", s.webParameters,
		&datadatdat.ListRemoteCommitsOpts{Tag: optional.NewInterface([]string{"a=b", "c=d"})})
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
	}
}

func (s *S3WebTestSuite) TestS3Web_030_CreateSecondCommit() {
	_, _, err := s.e.CommitApi.CreateCommit(s.ctx, "foo", datadatdat.Commit{
		Id:         "id2",
		Properties: map[string]interface{}{},
	})
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_031_PushWeb() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "web", "id2", s.webParameters, nil)
	if s.e.NoError(err) {
		progress, err := s.e.WaitForOperation(res.Id)
		s.Error(err)
		s.Equal("FAILED", progress[len(progress)-1].Type)
	}
}

func (s *S3WebTestSuite) TestS3Web_032_PushSecondCommit() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id2", s.s3parameters, nil)
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_033_ListMultipleCommits() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web", s.webParameters, nil)
	if s.e.NoError(err) {
		s.Len(res, 2)
		s.Equal("id2", res[0].Id)
		s.Equal("id", res[1].Id)
	}
}

func (s *S3WebTestSuite) TestS3Web_040_DeleteLocalCommits() {
	_, err := s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id")
	if s.e.NoError(err) {
		_, err = s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id2")
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_041_ListEmptyCommits() {
	res, _, err := s.e.CommitApi.ListCommits(s.ctx, "foo", nil)
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *S3WebTestSuite) TestS3Web_042_UpdateFile() {
	err := s.e.WriteFile("foo", "vol", "testfile", "Goodbye")
	if s.e.NoError(err) {
		res, err := s.e.ReadFile("foo", "vol", "testfile")
		if s.e.NoError(err) {
			s.Equal("Goodbye", res)
		}
	}
}

func (s *S3WebTestSuite) TestS3Web_043_PullCommit() {
	res, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "web", "id", s.webParameters, nil)
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_044_PullDuplicate() {
	_, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "web", "id", s.webParameters, nil)
	s.e.APIError(err, "ObjectExistsException")
}

func (s *S3WebTestSuite) TestS3Web_046_CheckoutCommit() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol")
	if s.e.NoError(err) {
		_, err := s.e.CommitApi.CheckoutCommit(s.ctx, "foo", "id")
		if s.e.NoError(err) {
			_, err = s.e.VolumeApi.ActivateVolume(s.ctx, "foo", "vol")
			s.e.NoError(err)
		}
	}
}

func (s *S3WebTestSuite) TestS3Web_047_OriginalContents() {
	res, err := s.e.ReadFile("foo", "vol", "testfile")
	if s.e.NoError(err) {
		s.Equal("Hello", res)
	}
}

func (s *S3WebTestSuite) TestS3Web_050_RemoveRemote() {
	_, err := s.e.RemoteApi.DeleteRemote(s.ctx, "foo", "origin")
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_051_DeleteVolume() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol")
	if s.e.NoError(err) {
		_, err = s.e.VolumeApi.DeleteVolume(s.ctx, "foo", "vol")
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_052_DeleteRepository() {
	_, err := s.e.RepoApi.DeleteRepository(s.ctx, "foo")
	s.e.NoError(err)
}
