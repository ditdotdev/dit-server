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
export MAVEN_BUCKET="dit-maven"
export AWS_ACCESS_KEY_ID="YOUR_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="YOUR_SECRET_KEY"

# Add secrets to command-executor
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/command-executor"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/command-executor"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/command-executor"

# Add secrets to remote-sdk
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/remote-sdk"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/remote-sdk"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/remote-sdk"

# Add secrets to s3-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/s3-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/s3-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/s3-remote"

# Add secrets to ssh-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/ssh-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/ssh-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/ssh-remote"

# Add secrets to s3web-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/s3web-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/s3web-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/s3web-remote"

# Add secrets to nop-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/nop-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/nop-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/nop-remote"

# Add secrets to plugin-launcher
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/plugin-launcher"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/plugin-launcher"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/plugin-launcher"

# Add secrets to delphix-remote
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/delphix-remote"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/delphix-remote"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/delphix-remote"

# Add secrets to dit-server
gh secret set MAVEN_BUCKET --body "$MAVEN_BUCKET" --repo "ditdotdev/dit-server"
gh secret set AWS_ACCESS_KEY_ID --body "$AWS_ACCESS_KEY_ID" --repo "ditdotdev/dit-server"
gh secret set AWS_SECRET_ACCESS_KEY --body "$AWS_SECRET_ACCESS_KEY" --repo "ditdotdev/dit-server"
```

## Step 5: Verify secrets were added
```bash
gh secret list --repo "ditdotdev/remote-sdk"
```
