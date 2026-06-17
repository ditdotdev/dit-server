/*
 * Copyright Dit.
 */
package remote

import (
	"context"
	"fmt"
	"os"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	dit "github.com/ditdotdev/dit-client-go"
	endtoend "github.com/ditdotdev/dit-server/test/common"
	"github.com/stretchr/testify/suite"
)

type S3WebTestSuite struct {
	suite.Suite
	e   *endtoend.EndToEndTest
	ctx context.Context

	s3bucket      string
	s3path        string
	s3remote      dit.Remote
	webRemote     dit.Remote
	s3parameters  dit.RemoteParameters
	webParameters dit.RemoteParameters

	// clearBucketFn lets unit tests substitute the bucket-clearing call. In
	// production it is wired to ClearBucket during SetupSuite. See issue #156.
	clearBucketFn func() error
}

func (s *S3WebTestSuite) ClearBucket() error {
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

func (s *S3WebTestSuite) SetupSuite() {
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

	s.s3remote = dit.Remote{
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
	s.webRemote = dit.Remote{
		Provider: "s3web",
		Name:     "web",
		Properties: map[string]interface{}{
			"url": fmt.Sprintf("http://%s.s3.amazonaws.com/%s", s.s3bucket, s.s3path),
		},
	}

	s.e = endtoend.NewEndToEndTest(&s.Suite, "docker-zfs")
	s.e.SetupStandardDocker()

	s.ctx = context.Background()

	s.s3parameters = dit.RemoteParameters{
		Provider:   "s3",
		Properties: map[string]interface{}{},
	}

	s.webParameters = dit.RemoteParameters{
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
	_, _, err := s.e.RepoApi.CreateRepository(s.ctx).Repository(dit.Repository{
		Name:       "foo",
		Properties: map[string]interface{}{},
	}).Execute()
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_002_CreateMountVolume() {
	_, _, err := s.e.VolumeApi.CreateVolume(s.ctx, "foo").Volume(dit.Volume{
		Name:       "vol",
		Properties: map[string]interface{}{},
	}).Execute()
	if s.e.NoError(err) {
		_, err := s.e.VolumeApi.ActivateVolume(s.ctx, "foo", "vol").Execute()
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

func (s *S3WebTestSuite) TestS3Web_005_AddS3Remote() {
	_, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo").Remote(s.s3remote).Execute()
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_006_AddS3WebRemote() {
	_, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo").Remote(s.webRemote).Execute()
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_010_ListEmptyRemoteCommits() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web").RemoteParameters(s.webParameters).Execute()
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *S3WebTestSuite) TestS3Web_011_GetBadRemoteCommit() {
	_, _, err := s.e.RemoteApi.GetRemoteCommit(s.ctx, "foo", "web", "id2").RemoteParameters(s.webParameters).Execute()
	s.e.APIError(err, "NoSuchObjectException")
}

func (s *S3WebTestSuite) TestS3Web_020_PushCommit() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id").RemoteParameters(s.s3parameters).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_021_ListRemoteCommit() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web").RemoteParameters(s.webParameters).Execute()
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
		s.Equal("b", s.e.GetTag(res[0], "a"))
	}
}

func (s *S3WebTestSuite) TestS3Web_022_ListRemoteFilterOut() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web").
		RemoteParameters(s.webParameters).Tag([]string{"e"}).Execute()
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *S3WebTestSuite) TestS3Web_023_ListRemoteFilterInclude() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web").
		RemoteParameters(s.webParameters).Tag([]string{"a=b", "c=d"}).Execute()
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
	}
}

func (s *S3WebTestSuite) TestS3Web_030_CreateSecondCommit() {
	_, _, err := s.e.CommitApi.CreateCommit(s.ctx, "foo").Commit(dit.Commit{
		Id:         "id2",
		Properties: map[string]interface{}{},
	}).Execute()
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_031_PushWeb() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "web", "id2").RemoteParameters(s.webParameters).Execute()
	if s.e.NoError(err) {
		progress, err := s.e.WaitForOperation(res.Id)
		s.Error(err)
		s.Equal("FAILED", progress[len(progress)-1].Type)
	}
}

func (s *S3WebTestSuite) TestS3Web_032_PushSecondCommit() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id2").RemoteParameters(s.s3parameters).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_033_ListMultipleCommits() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "web").RemoteParameters(s.webParameters).Execute()
	if s.e.NoError(err) {
		s.Len(res, 2)
		s.Equal("id2", res[0].Id)
		s.Equal("id", res[1].Id)
	}
}

func (s *S3WebTestSuite) TestS3Web_040_DeleteLocalCommits() {
	_, err := s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id").Execute()
	if s.e.NoError(err) {
		_, err = s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id2").Execute()
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_041_ListEmptyCommits() {
	res, _, err := s.e.CommitApi.ListCommits(s.ctx, "foo").Execute()
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
	res, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "web", "id").RemoteParameters(s.webParameters).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_044_PullDuplicate() {
	_, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "web", "id").RemoteParameters(s.webParameters).Execute()
	s.e.APIError(err, "ObjectExistsException")
}

func (s *S3WebTestSuite) TestS3Web_046_CheckoutCommit() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		_, err := s.e.CommitApi.CheckoutCommit(s.ctx, "foo", "id").Execute()
		if s.e.NoError(err) {
			_, err = s.e.VolumeApi.ActivateVolume(s.ctx, "foo", "vol").Execute()
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
	_, err := s.e.RemoteApi.DeleteRemote(s.ctx, "foo", "origin").Execute()
	s.e.NoError(err)
}

func (s *S3WebTestSuite) TestS3Web_051_DeleteVolume() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		_, err = s.e.VolumeApi.DeleteVolume(s.ctx, "foo", "vol").Execute()
		s.e.NoError(err)
	}
}

func (s *S3WebTestSuite) TestS3Web_052_DeleteRepository() {
	_, err := s.e.RepoApi.DeleteRepository(s.ctx, "foo").Execute()
	s.e.NoError(err)
}
