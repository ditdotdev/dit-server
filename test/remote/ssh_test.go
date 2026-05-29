/*
 * Copyright Datadatdat.
 */
package remote

import (
	"context"
	datadatdat "github.com/datadatdat/datadatdat-client-go"
	endtoend "github.com/datadatdat/datadatdat-server/test/common"
	"github.com/stretchr/testify/suite"
	"os"
	"testing"
)

type SshTestSuite struct {
	suite.Suite
	e   *endtoend.EndToEndTest
	ctx context.Context

	remoteParams datadatdat.RemoteParameters
}

func (s *SshTestSuite) SetupSuite() {
	s.e = endtoend.NewEndToEndTest(&s.Suite, "docker-zfs")
	s.e.SetupStandardDocker()
	s.e.SetupStandardSsh()

	s.ctx = context.Background()

	s.remoteParams = datadatdat.RemoteParameters{
		Provider:   "ssh",
		Properties: map[string]interface{}{},
	}
}

func (s *SshTestSuite) TearDownSuite() {
	s.e.TeardownStandardDocker()
	s.e.TeardownStandardSsh()
}

func TestSshTestSuite(t *testing.T) {
	suite.Run(t, new(SshTestSuite))
}

func (s *SshTestSuite) TestSsh_001_CreateRepository() {
	_, _, err := s.e.RepoApi.CreateRepository(s.ctx).Repository(datadatdat.Repository{
		Name:       "foo",
		Properties: map[string]interface{}{},
	}).Execute()
	s.e.NoError(err)
}

func (s *SshTestSuite) TestSsh_002_CreateMountVolume() {
	_, _, err := s.e.VolumeApi.CreateVolume(s.ctx, "foo").Volume(datadatdat.Volume{
		Name:       "vol",
		Properties: map[string]interface{}{},
	}).Execute()
	if s.e.NoError(err) {
		_, err := s.e.VolumeApi.ActivateVolume(s.ctx, "foo", "vol").Execute()
		s.e.NoError(err)
	}
}

func (s *SshTestSuite) TestSsh_003_CreateFile() {
	err := s.e.WriteFile("foo", "vol", "testfile", "Hello")
	if s.e.NoError(err) {
		res, err := s.e.ReadFile("foo", "vol", "testfile")
		if s.e.NoError(err) {
			s.Equal("Hello", res)
		}
	}
}

func (s *SshTestSuite) TestSsh_004_CreateCommit() {
	res, _, err := s.e.CommitApi.CreateCommit(s.ctx, "foo").Commit(datadatdat.Commit{
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

func (s *SshTestSuite) TestSsh_005_AddRemote() {
	err := s.e.MkdirSsh("/bar")
	if s.e.NoError(err) {
		res, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo").Remote(datadatdat.Remote{
			Provider: "ssh",
			Name:     "origin",
			Properties: map[string]interface{}{
				"address":  s.e.SshHost,
				"password": "test",
				"username": "test",
				"port":     22,
				"path":     "/bar",
				// Test server has an ephemeral host key that is never added
				// to known_hosts; opt out of strict host-key checking (the
				// supported way to reach a throwaway host since remote-sdk #48).
				"skipHostCheck": true,
			},
		}).Execute()
		if s.e.NoError(err) {
			s.Equal("origin", res.Name)
			s.Equal(s.e.SshHost, res.Properties["address"])
			s.Equal("test", res.Properties["username"])
			s.Equal("test", res.Properties["password"])
			s.Equal(22.0, res.Properties["port"])
			s.Equal("/bar", res.Properties["path"])
		}
	}
}

func (s *SshTestSuite) TestSsh_010_ListEmptyRemoteCommits() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *SshTestSuite) TestSsh_011_GetBadRemoteCommit() {
	_, _, err := s.e.RemoteApi.GetRemoteCommit(s.ctx, "foo", "origin", "id2").RemoteParameters(s.remoteParams).Execute()
	s.e.APIError(err, "NoSuchObjectException")
}

func (s *SshTestSuite) TestSsh_020_PushCommit() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *SshTestSuite) TestSsh_021_ListRemoteCommit() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
		s.Equal("b", s.e.GetTag(res[0], "a"))
	}
}

func (s *SshTestSuite) TestSsh_022_ListRemoteFilterOut() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
		RemoteParameters(s.remoteParams).Tag([]string{"e"}).Execute()
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *SshTestSuite) TestSsh_023_ListRemoteFilterInclude() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
		RemoteParameters(s.remoteParams).Tag([]string{"a=b", "c=d"}).Execute()
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
	}
}

func (s *SshTestSuite) TestSsh_024_RemoteFileContents() {
	res, err := s.e.ReadFileSsh("/bar/id/data/vol/testfile")
	if s.e.NoError(err) {
		s.Equal("Hello", res)
	}
}

func (s *SshTestSuite) TestSsh_030_PushDuplicateCommit() {
	_, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	s.e.APIError(err, "ObjectExistsException")
}

func (s *SshTestSuite) TestSsh_031_UpdateCommit() {
	res, _, err := s.e.CommitApi.UpdateCommit(s.ctx, "foo", "id").Commit(datadatdat.Commit{
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

func (s *SshTestSuite) TestSsh_032_PushMedata() {
	res, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id").
		RemoteParameters(s.remoteParams).MetadataOnly(true).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *SshTestSuite) TestSsh_033_RemoteMetadataUpdated() {
	res, _, err := s.e.RemoteApi.GetRemoteCommit(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		s.Equal("id", res.Id)
		s.Equal("B", s.e.GetTag(*res, "a"))
	}
}

func (s *SshTestSuite) TestSsh_040_DeleteLocalCommit() {
	_, err := s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id").Execute()
	s.e.NoError(err)
}

func (s *SshTestSuite) TestSsh_041_ListEmptyCommits() {
	res, _, err := s.e.CommitApi.ListCommits(s.ctx, "foo").Execute()
	if s.e.NoError(err) {
		s.Len(res, 0)
	}
}

func (s *SshTestSuite) TestSsh_042_UpdateFile() {
	err := s.e.WriteFile("foo", "vol", "testfile", "Goodbye")
	if s.e.NoError(err) {
		res, err := s.e.ReadFile("foo", "vol", "testfile")
		if s.e.NoError(err) {
			s.Equal("Goodbye", res)
		}
	}
}

func (s *SshTestSuite) TestSsh_043_PullCommit() {
	res, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *SshTestSuite) TestSsh_044_PullDuplicate() {
	_, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	s.e.APIError(err, "ObjectExistsException")
}

func (s *SshTestSuite) TestSsh_045_PullMetadata() {
	res, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").
		RemoteParameters(s.remoteParams).MetadataOnly(true).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(res.Id)
		s.e.NoError(err)
	}
}

func (s *SshTestSuite) TestSsh_046_CheckoutCommit() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		_, err := s.e.CommitApi.CheckoutCommit(s.ctx, "foo", "id").Execute()
		if s.e.NoError(err) {
			_, err = s.e.VolumeApi.ActivateVolume(s.ctx, "foo", "vol").Execute()
			s.e.NoError(err)
		}
	}
}

func (s *SshTestSuite) TestSsh_047_OriginalContents() {
	res, err := s.e.ReadFile("foo", "vol", "testfile")
	if s.e.NoError(err) {
		s.Equal("Hello", res)
	}
}

func (s *SshTestSuite) TestSsh_050_RemoveRemote() {
	_, err := s.e.RemoteApi.DeleteRemote(s.ctx, "foo", "origin").Execute()
	s.e.NoError(err)
}

func (s *SshTestSuite) TestSsh_051_AddRemoteNoPassword() {
	res, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo").Remote(datadatdat.Remote{
		Provider: "ssh",
		Name:     "origin",
		Properties: map[string]interface{}{
			"address":  s.e.SshHost,
			"username": "test",
			"port":     22,
			"path":     "/bar",
			// See TestSsh_005_AddRemote: skip strict host-key checking
			// against the throwaway ssh-test-server (ephemeral host key).
			"skipHostCheck": true,
		},
	}).Execute()
	if s.e.NoError(err) {
		s.Equal("origin", res.Name)
		s.Equal(s.e.SshHost, res.Properties["address"])
		s.Equal("test", res.Properties["username"])
		s.Nil(res.Properties["password"])
		s.Equal(22.0, res.Properties["port"])
		s.Equal("/bar", res.Properties["path"])
	}
}

func (s *SshTestSuite) TestSsh_052_ListCommitsPassword() {
	res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
		RemoteParameters(datadatdat.RemoteParameters{
			Provider:   "ssh",
			Properties: map[string]interface{}{"password": "test"},
		}).Execute()
	if s.e.NoError(err) {
		s.Len(res, 1)
		s.Equal("id", res[0].Id)
	}
}

func (s *SshTestSuite) TestSsh_053_ListCommitsNoPassword() {
	_, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").RemoteParameters(s.remoteParams).Execute()
	s.e.APIError(err, "IllegalArgumentException")
}

func (s *SshTestSuite) TestSsh_054_ListCommitsBadPassword() {
	_, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
		RemoteParameters(datadatdat.RemoteParameters{
			Provider:   "ssh",
			Properties: map[string]interface{}{"password": "r00t"},
		}).Execute()
	s.e.APIError(err, "CommandException")
}

func (s *SshTestSuite) TestSsh_060_CopyKey() {
	key, err := os.ReadFile("id_rsa.pub")
	if s.e.NoError(err) {
		err = s.e.WriteFileSsh("/home/test/.ssh/authorized_keys", string(key))
		s.e.NoError(err)
	}
}

func (s *SshTestSuite) TestSsh_061_ListCommitsKey() {
	key, err := os.ReadFile("id_rsa")
	if s.e.NoError(err) {
		res, _, err := s.e.RemoteApi.ListRemoteCommits(s.ctx, "foo", "origin").
			RemoteParameters(datadatdat.RemoteParameters{
				Provider:   "ssh",
				Properties: map[string]interface{}{"key": string(key)},
			}).Execute()
		if s.e.NoError(err) {
			s.Len(res, 1)
			s.Equal("id", res[0].Id)
		}
	}
}

func (s *SshTestSuite) TestSsh_062_PullCommitKey() {
	key, err := os.ReadFile("id_rsa")
	if s.e.NoError(err) {
		_, err := s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id").Execute()
		if s.e.NoError(err) {
			res, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").
				RemoteParameters(datadatdat.RemoteParameters{
					Provider:   "ssh",
					Properties: map[string]interface{}{"key": string(key)},
				}).Execute()
			if s.e.NoError(err) {
				_, err = s.e.WaitForOperation(res.Id)
				s.e.NoError(err)
			}
		}
	}
}

func (s *SshTestSuite) TestSsh_070_DeleteVolume() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		_, err = s.e.VolumeApi.DeleteVolume(s.ctx, "foo", "vol").Execute()
		s.e.NoError(err)
	}
}

func (s *SshTestSuite) TestSsh_071_DeleteRepository() {
	_, err := s.e.RepoApi.DeleteRepository(s.ctx, "foo").Execute()
	s.e.NoError(err)
}
