/*
 * Copyright Datadatdat.
 */
package common

import (
	"context"
	"errors"
	"fmt"
	"github.com/antihax/optional"
	datadatdat "github.com/datadatdat/datadatdat-client-go"
	"github.com/stretchr/testify/suite"
	"golang.org/x/crypto/ssh"
	"os"
	"os/exec"
	"os/user"
	"strings"
	"time"
)

const dockerZfsContext = "docker-zfs"

/*
 * Utility class for managing endtoend tests of datadatdat-server. There are two types of
 * containers we care about: the datadatdat server container and a remote SSH container. The datadatdat
 * server is run on an alternate pool and port so as not to conflict with the running datadatdat-server.
 * For the remote SSH server, we use 'rastasheep/ubuntu-sshd', which comes pre-built for remote access
 * over SSH.
 */
type EndToEndTest struct {
	*suite.Suite
	Context  string
	Identity string
	Port     int
	Image    string
	SshPort  int
	SshHost  string
	HomeDir  string

	Client *datadatdat.APIClient

	RepoApi       *datadatdat.RepositoriesApiService
	RemoteApi     *datadatdat.RemotesApiService
	VolumeApi     *datadatdat.VolumesApiService
	CommitApi     *datadatdat.CommitsApiService
	OperationsApi *datadatdat.OperationsApiService
}

const waitTimeout = 1
const waitRetries = 60
const sshUser = "test"
const sshPassword = "test"

func NewEndToEndTest(s *suite.Suite, context string) *EndToEndTest {
	ret := EndToEndTest{
		Suite:    s,
		Context:  context,
		Identity: "test",
		Port:     6001,
		Image:    "datadatdat:latest",
		SshPort:  6003,
	}

	cfg := datadatdat.NewConfiguration()
	cfg.Host = fmt.Sprintf("localhost:%d", ret.Port)
	ret.Client = datadatdat.NewAPIClient(cfg)

	ret.RepoApi = ret.Client.RepositoriesApi
	ret.VolumeApi = ret.Client.VolumesApi
	ret.RemoteApi = ret.Client.RemotesApi
	ret.CommitApi = ret.Client.CommitsApi
	ret.OperationsApi = ret.Client.OperationsApi

	usr, err := user.Current()
	if err != nil {
		panic(err)
	}
	ret.HomeDir = usr.HomeDir
	if ret.HomeDir == "" {
		panic("failed to determine user home directory")
	}

	return &ret
}

/*
 * Run a specific entry point within datadatdat-server. This can either run a full-fledged launch, or can be used to
 * teardown the test environment.
 */
func (e *EndToEndTest) RunDatadatdatDocker(entryPoint string, daemon bool) error {
	args := []string{"run", "--privileged", "--pid=host", "--network=host",
		"-v", "/var/lib:/var/lib", "-v", "/run/docker:/run/docker"}
	if daemon {
		args = append(args, "-d", "--restart", "always", "--name", e.GetPrimaryContainer(),
			"-v", fmt.Sprintf("/lib:/var/lib/%s/system", e.Identity))
	} else {
		args = append(args, "--rm")
	}
	args = append(args,
		"-v", fmt.Sprintf("%s-data:/var/lib/%s/data", e.Identity, e.Identity),
		"-v", "/var/run/docker.sock:/var/run/docker.sock",
		"-e", fmt.Sprintf("DATADATDAT_IDENTITY=%s", e.Identity),
		"-e", fmt.Sprintf("DATADATDAT_IMAGE=%s", e.Image),
		"-e", fmt.Sprintf("DATADATDAT_PORT=%d", e.Port),
		e.Image, "/bin/bash", fmt.Sprintf("/datadatdat/%s", entryPoint))

	return exec.Command("docker", args...).Run() // #nosec G204 - controlled docker command in test
}

/*
 * Run an entry point within kubernetes. This always spawns it as a daemon, and runs datadatdat-server directly without
 * going through the launch script.
 */
func (e *EndToEndTest) RunDatadatdatKubernetes(entryPoint string, parameters ...string) error {
	imageSpecified := false
	for _, p := range parameters {
		if strings.Index(p, "datadatdatImage=") == 0 {
			imageSpecified = true
			break
		}
	}
	if !imageSpecified {
		image := os.Getenv("DATADATDAT_IMAGE")
		if image == "" {
			image = "datadatdat/datadatdat:latest"
		}
		parameters = append(parameters, fmt.Sprintf("datadatdatImage=%s", image))
	}

	args := []string{
		"run", "-d", "--restart", "always", "--name", e.GetPrimaryContainer(),
		"-v", fmt.Sprintf("%s/.kube:/root/.kube", e.HomeDir),
		"-v", fmt.Sprintf("%s-data:/var/lib/%s", e.Identity, e.Identity),
		"-e", "DATADATDAT_CONTEXT=kubernetes-csi",
		"-e", fmt.Sprintf("DATADATDAT_IDENTITY=%s", e.Identity),
		"-e", fmt.Sprintf("DATADATDAT_CONFIG=%s", strings.Join(parameters, ",")),
		"-p", fmt.Sprintf("%d:5001", e.Port), e.Image, "/bin/bash",
		fmt.Sprintf("/datadatdat/%s", entryPoint),
	}

	return exec.Command("docker", args...).Run() // #nosec G204 - controlled docker command in test
}

/*
 * Start the server depending on the context type.
 */
func (e *EndToEndTest) StartServer(parameters ...string) error {
	err := exec.Command("docker", "volume", "create", fmt.Sprintf("%s-data", e.Identity)).Run() // #nosec G204 - controlled docker command in test
	if err != nil {
		return err
	}
	if e.Context == dockerZfsContext {
		return e.RunDatadatdatDocker("launch", true)
	} else {
		return e.RunDatadatdatKubernetes("run", parameters...)
	}
}

/*
 * Get a container name augmented with the identity, such as "test-server"
 */
func (e *EndToEndTest) GetContainer(t string) string {
	return fmt.Sprintf("%s-%s", e.Identity, t)
}

/*
 * Get the primary running container, either "launch" for docker-zfs or "server" to kubernetes.
 */
func (e *EndToEndTest) GetPrimaryContainer() string {
	var containerType string
	if e.Context == dockerZfsContext {
		containerType = "launch"
	} else {
		containerType = "server"
	}
	return e.GetContainer(containerType)
}

/*
 * Wait for the server to be ready.
 */
func (e *EndToEndTest) WaitForServer() error {
	success := false
	tried := 1
	for ok := true; ok; ok = !success {
		_, _, err := e.Client.RepositoriesApi.ListRepositories(context.Background())
		if err == nil {
			success = true
		} else {
			tried++
			if tried == waitRetries {
				logs, err := exec.Command("docker", "logs", e.GetPrimaryContainer()).CombinedOutput() // #nosec G204 - controlled docker logs command in test
				if err != nil {
					return err
				}
				return fmt.Errorf("timeoed out waiting for server to start: %s", logs)
			}
			time.Sleep(time.Duration(waitTimeout) * time.Second)
		}
	}
	return nil
}

/*
 * Restart the server. This only works for docker-zfs.
 */
func (e *EndToEndTest) RestartServer() error {
	return exec.Command("docker", "rm", "-f", e.GetContainer("server")).Run() // #nosec G204 - controlled docker rm command in test
}

/*
 * Stop the server completely, including the launch container for docker-zfs.
 */
func (e *EndToEndTest) StopServer(ignoreErrors bool) error {
	if e.Context == dockerZfsContext {
		err := exec.Command("docker", "rm", "-f", e.GetContainer("launch")).Run() // #nosec G204 - controlled docker rm command in test
		if err != nil && !ignoreErrors {
			return err
		}
	}
	err := exec.Command("docker", "rm", "-f", e.GetContainer("server")).Run() // #nosec G204 - controlled docker rm command in test
	if err != nil && !ignoreErrors {
		return err
	}

	if e.Context == dockerZfsContext {
		err = e.RunDatadatdatDocker("teardown", false)
		if err != nil && !ignoreErrors {
			return err
		}
	}

	err = exec.Command("docker", "volume", "rm", fmt.Sprintf("%s-data", e.Identity)).Run() // #nosec G204 - controlled docker command in test
	if err != nil && !ignoreErrors {
		return err
	}

	return nil
}

/*
 * Get the path of a volume.
 */
func (e *EndToEndTest) GetVolumePath(repo string, volume string) (string, error) {
	v, _, err := e.Client.VolumesApi.GetVolume(context.Background(), repo, volume)
	if err != nil {
		return "", err
	}
	return v.Config["mountpoint"].(string), nil
}

/*
 * Execute a command on the server container.
 */
func (e *EndToEndTest) ExecServer(args ...string) (string, error) {
	fullArgs := []string{"exec", e.GetContainer("server")}
	fullArgs = append(fullArgs, args...)
	out, err := exec.Command("docker", fullArgs...).Output() // #nosec G204 - controlled docker command in test
	if err != nil {
		return "", err
	}
	return string(out), nil
}

/*
 * Write to a file, relative to a particular volume. This uses a very simplistic "echo", so it will fail for
 * complex inputs (such as those with quotes).
 */
func (e *EndToEndTest) WriteFile(repo string, volume string, filename string, content string) error {
	mountpoint, err := e.GetVolumePath(repo, volume)
	if err != nil {
		return err
	}
	path := fmt.Sprintf("%s/%s", mountpoint, filename)
	return exec.Command("docker", "exec", e.GetContainer("server"), "sh", "-c", // #nosec G204 - controlled docker exec command in test
		fmt.Sprintf("echo -n \"%s\" > %s", content, path)).Run()
}

/*
 * Read the contents of a file on the server.
 */
func (e *EndToEndTest) ReadFile(repo string, volume string, filename string) (string, error) {
	mountpoint, err := e.GetVolumePath(repo, volume)
	if err != nil {
		return "", err
	}
	path := fmt.Sprintf("%s/%s", mountpoint, filename)
	out, err := exec.Command("docker", "exec", e.GetContainer("server"), "cat", path).Output() // #nosec G204 - controlled docker exec command in test
	if err != nil {
		return "", err
	}
	return string(out), nil
}

/*
 * Check to see whether the given path exists on the server
 */
func (e *EndToEndTest) PathExists(path string) bool {
	err := exec.Command("docker", "exec", e.GetContainer("server"), "ls", path).Run() // #nosec G204 - controlled docker command in test
	return err != nil
}

/*
 * Write to a file on the SSH server.
 */
func (e *EndToEndTest) WriteFileSsh(path string, content string) error {
	return exec.Command("docker", "exec", e.GetContainer("ssh"), "sh", "-c", // #nosec G204 - controlled docker exec command in test
		fmt.Sprintf("echo \"%s\" > %s", content, path)).Run()
}

func (e *EndToEndTest) ReadFileSsh(path string) (string, error) {
	out, err := exec.Command("docker", "exec", e.GetContainer("ssh"), "cat", path).Output() // #nosec G204 - controlled docker exec command in test
	if err != nil {
		return "", err
	}
	return string(out), nil
}

func (e *EndToEndTest) MkdirSsh(path string) error {
	err := exec.Command("docker", "exec", e.GetContainer("ssh"), "mkdir", "-p", path).Run() // #nosec G204 - controlled docker command in test
	if err != nil {
		return err
	}
	return exec.Command("docker", "exec", e.GetContainer("ssh"), "chown", sshUser, path).Run() // #nosec G204 - controlled docker command in test
}

func (e *EndToEndTest) StartSsh() error {
	return exec.Command("docker", "run", "-p", fmt.Sprintf("%d:22", e.SshPort), "-d", "--name", e.GetContainer("ssh"), // #nosec G204 - controlled docker run command in test
		"--network", e.Identity, "datadatdat/ssh-test-server:latest").Run()
}

func (e *EndToEndTest) StopSsh() error {
	return exec.Command("docker", "rm", "-f", e.GetContainer("ssh")).Run() // #nosec G204 - controlled docker rm command in test
}

func (e *EndToEndTest) WaitForSsh() error {
	success := false
	tried := 1
	for ok := true; ok; ok = !success {
		sshConfig := &ssh.ClientConfig{
			User:            sshUser,
			HostKeyCallback: ssh.InsecureIgnoreHostKey(), // #nosec G106 - testing environment SSH connection
			Auth: []ssh.AuthMethod{
				ssh.Password(sshPassword),
			},
		}
		connection, err := ssh.Dial("tcp", fmt.Sprintf("localhost:%d", e.SshPort), sshConfig)
		if err == nil {
			session, err := connection.NewSession()
			if err == nil {
				success = true
				_ = session.Close()
			}
			_ = connection.Close()
		}

		if !success {
			tried++
			if tried == waitRetries {
				logs, err := exec.Command("docker", "logs", e.GetContainer("test-ssh")).CombinedOutput() // #nosec G204 - controlled docker logs command in test
				if err != nil {
					return err
				}
				return fmt.Errorf("timed out waiting for SSH server to start: %s", logs)
			}
			time.Sleep(time.Duration(waitTimeout) * time.Second)
		}
	}

	out, err := exec.Command("docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", // #nosec G204 - controlled docker inspect command in test
		e.GetContainer("ssh")).Output()
	if err != nil {
		return err
	}
	e.SshHost = string(out)
	return nil
}

func (e *EndToEndTest) SetupStandardDocker() {
	_ = e.StopServer(true)
	err := e.StartServer()
	if err != nil {
		// Check if we should skip tests when ZFS setup fails
		if os.Getenv("SKIP_ZFS_E2E_ON_FAILURE") == "true" {
			e.Suite.T().Skipf("Skipping Docker E2E tests due to ZFS setup failure (SKIP_ZFS_E2E_ON_FAILURE=true): %v", err)
			return
		}
		panic(err)
	}
	err = e.WaitForServer()
	if err != nil {
		// Check if we should skip tests when server fails to start (likely ZFS issue)
		if os.Getenv("SKIP_ZFS_E2E_ON_FAILURE") == "true" {
			e.Suite.T().Skipf("Skipping Docker E2E tests due to server startup failure (SKIP_ZFS_E2E_ON_FAILURE=true): %v", err)
			return
		}
		panic(err)
	}
}

func (e *EndToEndTest) TeardownStandardDocker() {
	_ = e.StopServer(false)
}

func (e *EndToEndTest) SetupStandardSsh() {
	_ = e.StopSsh()
	err := e.StartSsh()
	if err != nil {
		panic(err)
	}
	err = e.WaitForSsh()
	if err != nil {
		panic(err)
	}
}

func (e *EndToEndTest) TeardownStandardSsh() {
	_ = e.StopSsh()
}

func (e *EndToEndTest) APIError(err error, code string) bool {
	if openApiError, ok := err.(datadatdat.GenericOpenAPIError); ok {
		if datadatdatApiError, ok := openApiError.Model().(datadatdat.ApiError); ok {
			return e.Equal(code, datadatdatApiError.Code, datadatdatApiError.Message)
		}
	}
	return e.Error(err)
}

func (e *EndToEndTest) NoError(err error) bool {
	if err != nil {
		if openApiError, ok := err.(datadatdat.GenericOpenAPIError); ok {
			if datadatdatApiError, ok := openApiError.Model().(datadatdat.ApiError); ok {
				return e.Fail("unexpected error", datadatdatApiError.Message)
			}
		}
	}
	return e.Suite.NoError(err)
}

func (e *EndToEndTest) GetTag(commit datadatdat.Commit, tag string) string {
	if tags, ok := commit.Properties["tags"].(map[string]interface{}); ok {
		return tags[tag].(string)
	}
	return ""
}

func (e *EndToEndTest) WaitForOperation(id string) ([]datadatdat.ProgressEntry, error) {
	completed := false
	var lastEntry int32 = 0
	result := []datadatdat.ProgressEntry{}
	for ok := true; ok; ok = !completed {
		progress, _, err := e.Client.OperationsApi.GetOperationProgress(context.Background(), id,
			&datadatdat.GetOperationProgressOpts{LastId: optional.NewInt32(lastEntry)})
		if err != nil {
			return nil, err
		}
		result = append(result, progress...)
		for _, p := range progress {
			switch p.Type {
			case "COMPLETE":
				completed = true
			case "ABORT":
				return result, fmt.Errorf("operation aborted: %s", p.Message)
			case "FAILED":
				return result, fmt.Errorf("operation failed: %s", p.Message)
			}
			if p.Id > lastEntry {
				lastEntry = p.Id
			}
		}
		if !completed {
			time.Sleep(time.Duration(500) * time.Millisecond)
		}
	}
	return result, nil
}

func (e *EndToEndTest) WaitForVolume(repo string, volume string) error {
	ready := false
	for ok := true; ok; ok = !ready {
		res, _, err := e.VolumeApi.GetVolumeStatus(context.Background(), repo, volume)
		if err != nil {
			return err
		}
		if res.Error != "" {
			return errors.New(res.Error)
		}
		ready = res.Ready
		if !ready {
			time.Sleep(time.Duration(1) * time.Second)
		}
	}
	return nil
}

func (e *EndToEndTest) WaitForCommit(repo string, id string) error {
	ready := false
	for ok := true; ok; ok = !ready {
		res, _, err := e.CommitApi.GetCommitStatus(context.Background(), repo, id)
		if err != nil {
			return err
		}
		if res.Error != "" {
			return errors.New(res.Error)
		}
		ready = res.Ready
		if !ready {
			time.Sleep(time.Duration(1) * time.Second)
		}
	}
	return nil
}
