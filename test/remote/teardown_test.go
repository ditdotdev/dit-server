/*
 * Copyright Datadatdat.
 */
package remote

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// These tests pin the contract that S3TestSuite and S3WebTestSuite call
// ClearBucket() during TearDownSuite. This is required for per-run S3 prefix
// isolation (issue #156): each CI run uses its own prefix, and the suite must
// clean up that prefix when it exits so the bucket does not grow unbounded.
//
// The tests do not require AWS credentials. They install a fake clearBucketFn
// onto a freshly-constructed suite and verify TearDownSuite invokes it. The
// real ClearBucket implementation is exercised in the AWS-backed E2E run.

func TestS3TestSuite_TearDownSuite_CallsClearBucket(t *testing.T) {
	calls := 0
	s := &S3TestSuite{}
	s.clearBucketFn = func() error {
		calls++
		return nil
	}

	s.TearDownSuite()

	assert.Equal(t, 1, calls,
		"S3TestSuite.TearDownSuite must call ClearBucket exactly once "+
			"so the per-run S3 prefix is cleaned up (issue #156)")
}

func TestS3WebTestSuite_TearDownSuite_CallsClearBucket(t *testing.T) {
	calls := 0
	s := &S3WebTestSuite{}
	s.clearBucketFn = func() error {
		calls++
		return nil
	}

	s.TearDownSuite()

	assert.Equal(t, 1, calls,
		"S3WebTestSuite.TearDownSuite must call ClearBucket exactly once "+
			"so the per-run S3 prefix is cleaned up (issue #156)")
}
