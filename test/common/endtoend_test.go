// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package common

import (
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// newArgsTest builds an EndToEndTest without touching the user's home
// directory or any API client; only the fields used by argument
// construction are set.
func newArgsTest() *EndToEndTest {
	return &EndToEndTest{
		Context:  "kubernetes-csi",
		Identity: "test",
		Port:     6001,
		Image:    "dit:latest",
		HomeDir:  "/home/e2e",
	}
}

// envValue returns the value of "-e NAME=value" in docker run args, or
// ok=false when NAME is not passed at all.
func envValue(args []string, name string) (string, bool) {
	prefix := name + "="
	for i := 0; i+1 < len(args); i++ {
		if args[i] == "-e" && strings.HasPrefix(args[i+1], prefix) {
			return strings.TrimPrefix(args[i+1], prefix), true
		}
	}
	return "", false
}

// Issue #327: the server's /dit/run reads DIT_CONTEXT_CONFIG (renamed in
// #208); the harness kept passing DIT_CONFIG, so no properties ever
// reached the server.
func TestKubernetesRunArgsPassesContextConfig(t *testing.T) {
	t.Setenv("DIT_IMAGE", "")
	e := newArgsTest()

	args := e.kubernetesRunArgs("run", "/tmp/flat-kubeconfig",
		"configFile=config", "context=minikube", "storageClass=sc", "snapshotClass=snap")

	got, ok := envValue(args, "DIT_CONTEXT_CONFIG")
	require.True(t, ok, "DIT_CONTEXT_CONFIG must be passed to the server container: %v", args)
	assert.Equal(t, "configFile=config,context=minikube,storageClass=sc,snapshotClass=snap,ditImage=ditdotdev/dit:latest", got)

	_, legacy := envValue(args, "DIT_CONFIG")
	assert.False(t, legacy, "DIT_CONFIG is not read by /dit/run and must not be passed: %v", args)
}

func TestKubernetesRunArgsDefaultImageFromEnv(t *testing.T) {
	t.Setenv("DIT_IMAGE", "example.com/dit:dev")
	e := newArgsTest()

	got, ok := envValue(e.kubernetesRunArgs("run", "/tmp/flat"), "DIT_CONTEXT_CONFIG")
	require.True(t, ok)
	assert.Equal(t, "ditImage=example.com/dit:dev", got)
}

func TestKubernetesRunArgsKeepsCallerImage(t *testing.T) {
	t.Setenv("DIT_IMAGE", "example.com/dit:dev")
	e := newArgsTest()

	got, ok := envValue(e.kubernetesRunArgs("run", "/tmp/flat", "ditImage=custom:1"), "DIT_CONTEXT_CONFIG")
	require.True(t, ok)
	assert.Equal(t, "ditImage=custom:1", got, "a caller-supplied ditImage must not be duplicated")
}

func TestKubernetesRunArgsContainerPlumbing(t *testing.T) {
	t.Setenv("DIT_IMAGE", "")
	e := newArgsTest()
	args := e.kubernetesRunArgs("run", "/home/e2e/.dit/e2e-kubeconfig-test")

	ctx, ok := envValue(args, "DIT_CONTEXT")
	require.True(t, ok)
	assert.Equal(t, "kubernetes-csi", ctx)
	id, ok := envValue(args, "DIT_IDENTITY")
	require.True(t, ok)
	assert.Equal(t, "test", id)

	joined := strings.Join(args, " ")
	assert.Contains(t, joined, " -p 6001:5001 ")
	assert.Contains(t, joined, " --name test-server ")
	assert.Contains(t, joined, " -v test-data:/var/lib/test ")
	// Issue #327 item 3: the flattened kubeconfig is mounted as the file
	// the server reads, not the whole ~/.kube directory.
	assert.Contains(t, joined, " -v /home/e2e/.dit/e2e-kubeconfig-test:/root/.kube/config:ro ")
	assert.NotContains(t, joined, "/home/e2e/.kube:/root/.kube")
	assert.True(t, strings.HasSuffix(joined, " dit:latest /bin/bash /dit/run"), joined)
}

// Issue #327 item 2: kubectl >= 1.3x rejects "exec POD COMMAND" outright
// ("exec [POD] [COMMAND] is not supported anymore"); the command must be
// separated from the pod by "--".
func TestKubectlExecArgsUsesDoubleDash(t *testing.T) {
	assert.Equal(t, []string{"exec", "p", "--", "cat", "/data/out"}, kubectlExecArgs("p", "cat", "/data/out"))
	assert.Equal(t, []string{"exec", "p", "--", "sh", "-c", "echo one > /data/out"},
		kubectlExecArgs("p", "sh", "-c", "echo one > /data/out"))
	assert.Equal(t, []string{"exec", "p", "--", "true"}, kubectlExecArgs("p", "true"))
}
