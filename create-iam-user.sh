#!/bin/bash

# Create IAM user and policies for GitHub Actions Maven publishing
set -e

BUCKET_NAME="dit-maven"
IAM_USER="github-actions-maven"
POLICY_NAME="MavenS3PublishPolicy"

echo "🔐 Creating IAM user for GitHub Actions Maven publishing"

# Check if AWS CLI is configured
if ! aws sts get-caller-identity >/dev/null 2>&1; then
    echo "❌ AWS CLI not configured. Please run 'aws configure' first."
    exit 1
fi

# Create IAM policy for S3 Maven publishing
echo "📝 Creating IAM policy..."
cat > maven-publish-policy.json << EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "s3:PutObject",
                "s3:PutObjectAcl",
                "s3:GetObject",
                "s3:DeleteObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "arn:aws:s3:::$BUCKET_NAME",
                "arn:aws:s3:::$BUCKET_NAME/*"
            ]
        }
    ]
}
EOF

# Create the policy
aws iam create-policy \
    --policy-name $POLICY_NAME \
    --policy-document file://maven-publish-policy.json \
    --description "Policy for GitHub Actions to publish Maven artifacts to S3"

POLICY_ARN=$(aws iam list-policies --query "Policies[?PolicyName=='$POLICY_NAME'].Arn" --output text)
echo "✅ Policy created: $POLICY_ARN"

# Create IAM user
echo "👤 Creating IAM user..."
aws iam create-user --user-name $IAM_USER
echo "✅ User created: $IAM_USER"

# Attach policy to user
echo "🔗 Attaching policy to user..."
aws iam attach-user-policy --user-name $IAM_USER --policy-arn $POLICY_ARN
echo "✅ Policy attached"

# Create access keys
echo "🔑 Creating access keys..."
KEY_OUTPUT=$(aws iam create-access-key --user-name $IAM_USER --output json)

ACCESS_KEY_ID=$(echo $KEY_OUTPUT | jq -r '.AccessKey.AccessKeyId')
SECRET_ACCESS_KEY=$(echo $KEY_OUTPUT | jq -r '.AccessKey.SecretAccessKey')

echo ""
echo "🎉 Setup complete!"
echo ""
echo "📋 GitHub Secrets to add:"
echo "================================"
echo "MAVEN_BUCKET: $BUCKET_NAME"
echo "AWS_ACCESS_KEY_ID: $ACCESS_KEY_ID"
echo "AWS_SECRET_ACCESS_KEY: $SECRET_ACCESS_KEY"
echo "================================"
echo ""
echo "⚠️  IMPORTANT: Save these credentials securely!"
echo "   The secret access key will not be shown again."
echo ""
echo "📝 Next steps:"
echo "1. Add these secrets to your GitHub repositories"
echo "2. Update build.gradle.kts files to use new bucket"
echo "3. Test publishing with a tag"

# Cleanup
rm -f maven-publish-policy.json
