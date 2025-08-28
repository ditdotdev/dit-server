# Manual GitHub CLI Commands to Add Secrets

## Step 1: Restart your terminal and authenticate
```bash
# Restart terminal first, then run:
gh auth login --web
```

## Step 2: Verify authentication
```bash
gh auth status
```

## Step 3: Get your AWS credentials (run these to see your values)
```bash
aws configure get aws_access_key_id
aws configure get aws_secret_access_key
```

## Step 4: Add secrets to each repository
Run these commands one by one (replace YOUR_ACCESS_KEY and YOUR_SECRET_KEY with actual values):

```bash
# Set variables (replace with your actual values)
export MAVEN_BUCKET="datadatdat-maven"
export AWS_ACCESS_KEY_ID="YOUR_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="YOUR_SECRET_KEY"

# Add secrets to command-executor
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/command-executor"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/command-executor"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/command-executor"

# Add secrets to remote-sdk
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/remote-sdk"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/remote-sdk"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/remote-sdk"

# Add secrets to s3-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/s3-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/s3-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/s3-remote"

# Add secrets to ssh-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/ssh-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/ssh-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/ssh-remote"

# Add secrets to s3web-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/s3web-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/s3web-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/s3web-remote"

# Add secrets to nop-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/nop-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/nop-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/nop-remote"

# Add secrets to plugin-launcher
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/plugin-launcher"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/plugin-launcher"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/plugin-launcher"

# Add secrets to delphix-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/delphix-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/delphix-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/delphix-remote"

# Add secrets to titan-server
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "datadatdat/titan-server"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "datadatdat/titan-server"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "datadatdat/titan-server"
```

## Step 5: Verify secrets were added
```bash
gh secret list --repo "datadatdat/remote-sdk"
```
