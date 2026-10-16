// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package common

import (
	"fmt"
	"os"
	"path/filepath"

	"k8s.io/client-go/tools/clientcmd"
	clientcmdapi "k8s.io/client-go/tools/clientcmd/api"
)

// flattenKubeconfig reads the kubeconfig at srcPath, inlines any
// certificate-authority / client-certificate / client-key file references as
// their base64 *-data counterparts, and writes the result to dstPath, creating
// parent directories as needed.
//
// The dit server container only sees the kubeconfig the harness mounts; it has
// no access to ~/.minikube (or equivalent) holding the referenced cert files,
// and on Windows those references are host-absolute paths that a Linux
// container cannot resolve regardless (issue #327). This mirrors
// FlattenKubeconfigToFile in the dit CLI.
func flattenKubeconfig(srcPath, dstPath string) error {
	cfg, err := clientcmd.LoadFromFile(srcPath)
	if err != nil {
		return fmt.Errorf("load %s: %w", srcPath, err)
	}
	if err := clientcmdapi.FlattenConfig(cfg); err != nil {
		return fmt.Errorf("flatten %s: %w", srcPath, err)
	}
	if err := os.MkdirAll(filepath.Dir(dstPath), 0750); err != nil {
		return fmt.Errorf("mkdir %s: %w", filepath.Dir(dstPath), err)
	}
	if err := clientcmd.WriteToFile(*cfg, dstPath); err != nil {
		return fmt.Errorf("write %s: %w", dstPath, err)
	}
	return nil
}
