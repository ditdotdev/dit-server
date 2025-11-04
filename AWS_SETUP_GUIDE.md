# AWS Configuration Guide

## When you run 'aws configure', enter these values:

1. **AWS Access Key ID**: [Paste your Access Key ID here]
   - Format: AKIA... (about 20 characters)
   - Found in: AWS Console → Security credentials → Access keys

2. **AWS Secret Access Key**: [Paste your Secret Access Key here]  
   - Format: Long string (about 40 characters)
   - ⚠️ Only shown once when created - save it securely!

3. **Default region name**: us-west-2
   - This is the region where our S3 buckets are located
   - Other good options: us-east-1, eu-west-1

4. **Default output format**: json
   - Makes responses easier to read and parse

## Example Configuration Session:
```
$ aws configure
AWS Access Key ID [None]: AKIAIOSFODNN7EXAMPLE
AWS Secret Access Key [None]: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
Default region name [None]: us-west-2
Default output format [None]: json
```

## Next Steps After Configuration:
1. Test with: aws sts get-caller-identity
2. Create S3 bucket: datadatdat-maven
3. Set up IAM user for GitHub Actions
