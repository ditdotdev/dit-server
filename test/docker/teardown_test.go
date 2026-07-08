// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package docker

import (
	"context"
	ditclient "github.com/ditdotdev/dit-client-go"
	endtoend "github.com/ditdotdev/dit-server/test/common"
	"github.com/stretchr/testify/suite"
	"testing"
)

type TeardownTestSuite struct {
	suite.Suite
	e *endtoend.EndToEndTest
}

func (s *TeardownTestSuite) SetupSuite() {
	s.e = endtoend.NewEndToEndTest(&s.Suite, "docker-zfs")
	s.e.SetupStandardDocker()
}

func (s *TeardownTestSuite) TearDownSuite() {
	s.e.TeardownStandardDocker()
}

func (s *TeardownTestSuite) TestTeardown_001_CreateRepository() {
	_, _, err := s.e.Client.RepositoriesApi.CreateRepository(context.Background()).Repository(ditclient.Repository{
		Name:       "foo",
		Properties: map[string]interface{}{"a": "b"},
	}).Execute()
	s.e.NoError(err)
}

func (s *TeardownTestSuite) TestTeardown_002_GetRepository() {
	repo, _, err := s.e.Client.RepositoriesApi.GetRepository(context.Background(), "foo").Execute()
	if s.e.NoError(err) {
		s.Equal("foo", repo.Name)
		s.Len(repo.Properties, 1)
		s.Equal("b", repo.Properties["a"])
	}
}

func (s *TeardownTestSuite) TestTeardown_003_Restart() {
	err := s.e.RestartServer()
	s.e.NoError(err)
	err = s.e.WaitForServer()
	s.e.NoError(err)
	repo, _, err := s.e.Client.RepositoriesApi.GetRepository(context.Background(), "foo").Execute()
	if s.e.NoError(err) {
		s.NotNil(repo)
		s.Equal("foo", repo.Name)
	}
}

func (s *TeardownTestSuite) TestTeardown_004_RestartTeardown() {
	err := s.e.StopServer(false)
	s.e.NoError(err)
	err = s.e.StartServer()
	s.e.NoError(err)
	err = s.e.WaitForServer()
	s.e.NoError(err)
	_, _, err = s.e.Client.RepositoriesApi.GetRepository(context.Background(), "foo").Execute()
	s.e.APIError(err, "NoSuchObjectException")
}

func TestTeardownTestSuite(t *testing.T) {
	suite.Run(t, new(TeardownTestSuite))
}
