/*
 * Copyright Dit.
 */
package kubernetes

import (
	"context"
	"fmt"
	dit "github.com/ditdotdev/dit-client-go"
	endtoend "github.com/ditdotdev/dit-server/test/common"
	"github.com/google/uuid"
	"github.com/stretchr/testify/suite"
	coreV1 "k8s.io/api/core/v1"
	apiV1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

type KubernetesWorkflowTestSuite struct {
	suite.Suite
	e *endtoend.EndToEndTest
	*kubernetes.Clientset
	namespace    string
	ctx          context.Context
	uuid         string
	pod1         string
	pod2         string
	remote       dit.Remote
	remoteParams dit.RemoteParameters
}

func (s *KubernetesWorkflowTestSuite) SetupSuite() {
	s.e = endtoend.NewEndToEndTest(&s.Suite, "kubernetes-csi")
	_ = s.e.StopServer(true)

	config := []string{}
	configEnv := os.Getenv("KUBERNETES_CONFIG")
	if configEnv != "" {
		config = strings.Split(configEnv, ",")
	}
	_ = s.e.StopServer(true)
	err := s.e.StartServer(config...)
	if err != nil {
		panic(err)
	}
	err = s.e.WaitForServer()
	if err != nil {
		panic(err)
	}

	cfg, err := clientcmd.BuildConfigFromFlags("", fmt.Sprintf("%s/.kube/config", s.e.HomeDir))
	if err != nil {
		panic(err)
	}

	cs, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		panic(err)
	}

	uuid, err := uuid.NewRandom()
	if err != nil {
		panic(err)
	}
	s.uuid = uuid.String()
	s.pod1 = fmt.Sprintf("%s-test", s.uuid)
	s.pod2 = fmt.Sprintf("%s-test2", s.uuid)
	s.Clientset = cs
	s.namespace = "default"
	s.ctx = context.Background()
	s.remote = dit.Remote{
		Provider:   "nop",
		Name:       "origin",
		Properties: map[string]interface{}{},
	}
	s.remoteParams = dit.RemoteParameters{
		Provider:   "nop",
		Properties: map[string]interface{}{},
	}
}

func (s *KubernetesWorkflowTestSuite) TearDownSuite() {
	err := s.e.StopServer(false)
	if err != nil {
		panic(err)
	}
}

func TestKubernetesWorkflowTestSuite(t *testing.T) {
	suite.Run(t, new(KubernetesWorkflowTestSuite))
}

func (s *KubernetesWorkflowTestSuite) WaitForPod(name string) error {
	ready := false
	for ok := true; ok; ok = !ready {
		res, err := s.Clientset.CoreV1().Pods(s.namespace).Get(context.TODO(), name, apiV1.GetOptions{})
		if err != nil {
			return err
		}

		if len(res.Status.ContainerStatuses) != 0 {
			ready = true
		}
		for _, container := range res.Status.ContainerStatuses {
			if container.RestartCount != 0 {
				return fmt.Errorf("container %s restarted %d times", name, container.RestartCount)
			}
			if !container.Ready {
				ready = false
			}
		}

		if !ready {
			time.Sleep(time.Duration(1) * time.Second)
		}
	}
	return nil
}

func (s *KubernetesWorkflowTestSuite) LaunchPod(name string, claim string) error {
	_, err := s.Clientset.CoreV1().Pods(s.namespace).Create(context.TODO(), &coreV1.Pod{
		ObjectMeta: apiV1.ObjectMeta{
			Name: name,
		},
		Spec: coreV1.PodSpec{
			Containers: []coreV1.Container{{
				Name:    "test",
				Image:   "ubuntu:bionic",
				Command: []string{"/bin/sh"},
				Args:    []string{"-c", "while true; do sleep 5; done"},
				VolumeMounts: []coreV1.VolumeMount{{
					Name:      "data",
					MountPath: "/data",
				}},
			}},
			Volumes: []coreV1.Volume{{
				Name: "data",
				VolumeSource: coreV1.VolumeSource{
					PersistentVolumeClaim: &coreV1.PersistentVolumeClaimVolumeSource{
						ClaimName: claim,
					},
				},
			}},
		},
	}, apiV1.CreateOptions{})
	return err
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_001_GetContext() {
	res, _, err := s.e.Client.ContextsApi.GetContext(context.Background()).Execute()
	if s.e.NoError(err) {
		s.Equal("kubernetes-csi", res.Provider)
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_002_Kubectl() {
	_, err := s.e.ExecServer("kubectl", "cluster-info")
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_010_CreateRepository() {
	_, _, err := s.e.RepoApi.CreateRepository(s.ctx).Repository(dit.Repository{
		Name:       "foo",
		Properties: map[string]interface{}{},
	}).Execute()
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_011_CreateVolume() {
	_, _, err := s.e.VolumeApi.CreateVolume(s.ctx, "foo").Volume(dit.Volume{
		Name:       "vol",
		Properties: map[string]interface{}{},
	}).Execute()
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_012_LaunchPod() {
	vol, _, err := s.e.VolumeApi.GetVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		pvc := vol.Config["pvc"].(string)
		err = s.LaunchPod(s.pod1, pvc)
		if s.e.NoError(err) {
			err = s.WaitForPod(s.pod1)
			s.e.NoError(err)
		}
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_013_WriteData() {
	err := exec.Command("kubectl", "exec", s.pod1, "--", "sh", "-c", "echo one > /data/out; sync; sleep 1;").Run() // #nosec G204,G702 - controlled kubectl command in test
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_014_VolumeStatus() {
	err := s.e.WaitForVolume("foo", "vol")
	if s.e.NoError(err) {
		res, _, err := s.e.VolumeApi.GetVolumeStatus(s.ctx, "foo", "vol").Execute()
		if s.e.NoError(err) {
			s.True(res.Ready)
			s.Empty(res.GetError())
		}
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_020_CreateCommit() {
	_, _, err := s.e.CommitApi.CreateCommit(s.ctx, "foo").Commit(dit.Commit{
		Id:         "id",
		Properties: map[string]interface{}{},
	}).Execute()
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_021_CommitStatus() {
	err := s.e.WaitForCommit("foo", "id")
	if s.e.NoError(err) {
		res, _, err := s.e.CommitApi.GetCommitStatus(s.ctx, "foo", "id").Execute()
		if s.e.NoError(err) {
			s.True(res.Ready)
			s.Empty(res.GetError())
		}
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_022_UpdateData() {
	err := exec.Command("kubectl", "exec", s.pod1, "--", "sh", "-c", "echo two > /data/out; sync; sleep 1;").Run() // #nosec G204,G702 - controlled kubectl command in test
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_023_DeletePod() {
	err := exec.Command("kubectl", "delete", "pod", "--grace-period=0", "--force", s.pod1).Run() // #nosec G204,G702 - controlled kubectl command in test
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_030_Checkout() {
	_, err := s.e.CommitApi.CheckoutCommit(s.ctx, "foo", "id").Execute()
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_031_LaunchNewPod() {
	vol, _, err := s.e.VolumeApi.GetVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		pvc := vol.Config["pvc"].(string)
		err = s.LaunchPod(s.pod2, pvc)
		if s.e.NoError(err) {
			err = s.WaitForPod(s.pod2)
			s.e.NoError(err)
		}
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_032_VerifyContents() {
	out, err := exec.Command("kubectl", "exec", s.pod2, "cat", "/data/out").Output() // #nosec G204,G702 - controlled kubectl command in test
	if s.e.NoError(err) {
		s.Equal("one", strings.TrimSpace(string(out)))
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_033_DeleteClonedPod() {
	err := exec.Command("kubectl", "delete", "pod", "--grace-period=0", "--force", s.pod2).Run() // #nosec G204,G702 - controlled kubectl command in test
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_040_AddRemote() {
	_, _, err := s.e.RemoteApi.CreateRemote(s.ctx, "foo").Remote(s.remote).Execute()
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_041_Push() {
	op, _, err := s.e.OperationsApi.Push(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(op.Id)
		s.e.NoError(err)
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_042_DeleteCommit() {
	_, err := s.e.CommitApi.DeleteCommit(s.ctx, "foo", "id").Execute()
	s.e.NoError(err)
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_043_Pull() {
	op, _, err := s.e.OperationsApi.Pull(s.ctx, "foo", "origin", "id").RemoteParameters(s.remoteParams).Execute()
	if s.e.NoError(err) {
		_, err = s.e.WaitForOperation(op.Id)
		s.e.NoError(err)
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_070_DeleteVolume() {
	_, err := s.e.VolumeApi.DeactivateVolume(s.ctx, "foo", "vol").Execute()
	if s.e.NoError(err) {
		_, err = s.e.VolumeApi.DeleteVolume(s.ctx, "foo", "vol").Execute()
		s.e.NoError(err)
	}
}

func (s *KubernetesWorkflowTestSuite) TestKubernetesConfig_071_DeleteRepository() {
	_, err := s.e.RepoApi.DeleteRepository(s.ctx, "foo").Execute()
	s.e.NoError(err)
}
