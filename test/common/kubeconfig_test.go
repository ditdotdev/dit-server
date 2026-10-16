// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package common

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Issue #327 item 3: minikube writes certificate-authority / client-certificate /
// client-key as host file paths (on Windows, C:\Users\...\.minikube\ca.crt). The
// dit server container only sees the kubeconfig we mount, so those references must
// be inlined as *-data fields before mounting, exactly as the CLI does.
func TestFlattenKubeconfigInlinesFileReferences(t *testing.T) {
	tmp := t.TempDir()
	caPath := filepath.Join(tmp, "ca.crt")
	certPath := filepath.Join(tmp, "client.crt")
	keyPath := filepath.Join(tmp, "client.key")

	files := map[string]string{
		caPath:   "-----BEGIN CERTIFICATE-----\nCA-PLACEHOLDER\n-----END CERTIFICATE-----\n",
		certPath: "-----BEGIN CERTIFICATE-----\nCLIENT-CERT-PLACEHOLDER\n-----END CERTIFICATE-----\n",
		keyPath:  "CLIENT-KEY-PLACEHOLDER\n",
	}
	for path, data := range files {
		if err := os.WriteFile(path, []byte(data), 0600); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
	}

	srcConfig := `apiVersion: v1
kind: Config
clusters:
- cluster:
    certificate-authority: ` + caPath + `
    server: https://127.0.0.1:8443
  name: minikube
contexts:
- context:
    cluster: minikube
    user: minikube
  name: minikube
current-context: minikube
users:
- name: minikube
  user:
    client-certificate: ` + certPath + `
    client-key: ` + keyPath + `
`
	srcPath := filepath.Join(tmp, "config")
	if err := os.WriteFile(srcPath, []byte(srcConfig), 0600); err != nil {
		t.Fatalf("write src config: %v", err)
	}

	// Nested parent directory that does not exist yet (mirrors ~/.dit/).
	dstPath := filepath.Join(tmp, "nested", "e2e-kubeconfig-test")

	if err := flattenKubeconfig(srcPath, dstPath); err != nil {
		t.Fatalf("flattenKubeconfig: %v", err)
	}

	out, err := os.ReadFile(dstPath) // #nosec G304 -- path under t.TempDir() written by this test
	if err != nil {
		t.Fatalf("read dst: %v", err)
	}
	outStr := string(out)

	for _, path := range []string{caPath, certPath, keyPath} {
		if strings.Contains(outStr, path) {
			t.Errorf("flattened config still references %q:\n%s", path, outStr)
		}
	}
	for _, field := range []string{"certificate-authority-data", "client-certificate-data", "client-key-data"} {
		if !strings.Contains(outStr, field) {
			t.Errorf("flattened config is missing %q:\n%s", field, outStr)
		}
	}
	if !strings.Contains(outStr, "current-context: minikube") {
		t.Errorf("flattened config lost current-context:\n%s", outStr)
	}
}

func TestFlattenKubeconfigMissingSource(t *testing.T) {
	tmp := t.TempDir()
	err := flattenKubeconfig(filepath.Join(tmp, "does-not-exist"), filepath.Join(tmp, "out"))
	if err == nil {
		t.Fatal("expected an error for a missing source kubeconfig")
	}
}
