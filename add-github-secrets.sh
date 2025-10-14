#!/bin/bash

# Script to add GitHub secrets to all Datadatdat repositories
# Run this after installing and authenticating with GitHub CLI

set -e

# Repository list
REPOS=(
    "datadatdat/command-executor"
    "datadatdat/delphix-remote"
    "datadatdat/nop-remote" 
    "datadatdat/plugin-launcher"
    "datadatdat/remote-sdk"
    "datadatdat/s3-remote"
    "datadatdat/s3web-remote"
    "datadatdat/ssh-remote"
    "datadatdat/datadatdat-server"
)

# Secrets to add
MAVEN_BUCKET="datadatdat-maven"

echo "🔐 Adding GitHub secrets to all Datadatdat repositories..."
echo ""

# Check if GitHub CLI is available and authenticated
if ! command -v gh &> /dev/null; then
    echo "❌ GitHub CLI (gh) is not found in PATH"
    echo "   Please restart your terminal and try again"
    exit 1
fi

if ! gh auth status &> /dev/null; then
    echo "🔑 GitHub CLI not authenticated. Please run:"
    echo "   gh auth login --web"
    exit 1
fi

# Get AWS credentials from current AWS CLI config
echo "📋 Reading AWS credentials..."
AWS_ACCESS_KEY_ID=$(aws configure get aws_access_key_id)
AWS_SECRET_ACCESS_KEY=$(aws configure get aws_secret_access_key)

if [ -z "$AWS_ACCESS_KEY_ID" ] || [ -z "$AWS_SECRET_ACCESS_KEY" ]; then
    echo "❌ AWS credentials not found. Please run 'aws configure' first."
    exit 1
fi

echo "✅ Found AWS credentials (ending in ${AWS_ACCESS_KEY_ID: -4})"
echo ""

# Add secrets to each repository
for repo in "${REPOS[@]}"; do
    echo "🔧 Adding secrets to $repo..."
    
    # Add MAVEN_BUCKET
    if gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "$repo"; then
        echo "  ✅ MAVEN_BUCKET added"
    else
        echo "  ❌ Failed to add MAVEN_BUCKET"
    fi
    
    # Add AWS_ACCESS_KEY_ID
    if gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "$repo"; then
        echo "  ✅ AWS_ACCESS_KEY_ID added"
    else
        echo "  ❌ Failed to add AWS_ACCESS_KEY_ID"
    fi
    
    # Add AWS_SECRET_ACCESS_KEY
    if gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "$repo"; then
        echo "  ✅ AWS_SECRET_ACCESS_KEY added"
    else
        echo "  ❌ Failed to add AWS_SECRET_ACCESS_KEY"
    fi
    
    echo ""
done

echo "🎉 Finished adding secrets to all repositories!"
echo ""
echo "📋 Summary:"
echo "  - Repositories: ${#REPOS[@]}"
echo "  - Secrets per repo: 3"
echo "  - Total secrets added: $((${#REPOS[@]} * 3))"
echo ""
echo "🧪 You can now test publishing by creating git tags!"
