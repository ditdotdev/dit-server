#!/bin/bash

# Setup script for datadatdat-maven S3 bucket
set -e

BUCKET_NAME="datadatdat-maven"
REGION="us-east-1"

echo "🚀 Setting up Maven S3 bucket: $BUCKET_NAME"

# Check if AWS CLI is configured
if ! aws sts get-caller-identity >/dev/null 2>&1; then
    echo "❌ AWS CLI not configured. Please run 'aws configure' first."
    exit 1
fi

# Create the bucket
echo "📦 Creating S3 bucket..."
if aws s3 mb s3://$BUCKET_NAME --region $REGION; then
    echo "✅ Bucket created successfully"
else
    echo "⚠️  Bucket might already exist or there was an error"
fi

# Set bucket policy for Maven publishing
echo "🔒 Setting bucket policy..."
cat > bucket-policy.json << EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "MavenPublishAccess",
            "Effect": "Allow",
            "Principal": {
                "AWS": "arn:aws:iam::$(aws sts get-caller-identity --query Account --output text):root"
            },
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

aws s3api put-bucket-policy --bucket $BUCKET_NAME --policy file://bucket-policy.json
echo "✅ Bucket policy applied"

# Enable versioning (recommended for Maven repos)
echo "📝 Enabling versioning..."
aws s3api put-bucket-versioning --bucket $BUCKET_NAME --versioning-configuration Status=Enabled
echo "✅ Versioning enabled"

# Test bucket access
echo "🧪 Testing bucket access..."
echo "test" | aws s3 cp - s3://$BUCKET_NAME/test.txt
aws s3 rm s3://$BUCKET_NAME/test.txt
echo "✅ Bucket access verified"

echo "🎉 Bucket setup complete!"
echo ""
echo "📋 Next steps:"
echo "1. Create IAM user for GitHub Actions"
echo "2. Generate access keys"
echo "3. Update GitHub repository secrets"

# Cleanup
rm -f bucket-policy.json
