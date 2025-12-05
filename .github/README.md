# GitHub Actions Setup for Release APK

This repository is configured to automatically build and release APKs when you create a GitHub release.

## Prerequisites

You need to set up the following secrets in your GitHub repository:

1. Go to your repository on GitHub
2. Navigate to Settings → Secrets and variables → Actions
3. Add the following repository secrets:

### Required Secrets

#### `KEYSTORE_BASE64`
Your signing keystore encoded in base64.

To generate this:
```bash
base64 -i your-keystore.jks | pbcopy
```
Then paste the output into the secret value.

#### `KEYSTORE_PASSWORD`
The password for your keystore file.

#### `KEY_ALIAS`
The alias of the key in your keystore.

#### `KEY_PASSWORD`
The password for the key.

## How to Use

### Creating a Release

1. Create and push a git tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

2. Go to your repository on GitHub → Releases → "Draft a new release"

3. Choose the tag you just created (e.g., `v1.0.0`)

4. Fill in the release title and description

5. Click "Publish release"

6. The GitHub Action will automatically:
   - Build the release APK
   - Generate a changelog from your commits
   - Upload the APK to the release

### Changelog Generation

The action automatically generates a changelog from your commit messages. To make use of this:

- Prefix feature commits with `feat:` (e.g., `feat: add new blur effect`)
- Prefix bug fix commits with `fix:` (e.g., `fix: resolve memory leak in renderer`)

These will be categorized in the changelog.

## Testing Locally

To test if the build will work with the same parameters:

```bash
./gradlew shimmer:assembleRelease \
  -Pversion.name=1.0.0 \
  -Pversion.code=1
```

## Build Artifacts

After a successful release build, you'll find:
- The APK attached to the GitHub release
- Build artifacts available for 90 days in the Actions tab

