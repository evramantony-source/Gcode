# Build configuration and signing

CI needs one repository **variable** and, for releases, four repository **secrets**. None of these
values are ever committed to the repository.

| Name | Kind | Needed for | Required |
|---|---|---|---|
| `GCODE_GITHUB_CLIENT_ID` | Variable | every build | yes |
| `KEYSTORE_BASE64` | Secret | signed releases (`v*` tags) | for releases |
| `KEYSTORE_PASSWORD` | Secret | signed releases | for releases |
| `KEY_ALIAS` | Secret | signed releases | for releases |
| `KEY_PASSWORD` | Secret | signed releases | for releases |
| `DEBUG_KEYSTORE_BASE64` | Secret | stable debug signing | optional |

Add variables and secrets under **your repository > Settings > Secrets and variables > Actions**
(the *Variables* tab for variables, the *Secrets* tab for secrets), or with the GitHub CLI as shown
below.

---

## 1. GitHub OAuth App client ID (`GCODE_GITHUB_CLIENT_ID`)

GCode signs in to GitHub with the OAuth **device flow**. It needs the *client ID* of an OAuth App.
A client ID is public, not a secret, but it is never hard-coded: the build reads it from this
variable and **fails with a clear message if it is missing**.

1. Open <https://github.com/settings/developers> > **OAuth Apps** > **New OAuth App**.
2. Fill in:
   * **Application name:** `GCode`
   * **Homepage URL:** your repository URL
   * **Authorization callback URL:** your repository URL (the device flow does not use it, but
     GitHub requires a value)
3. Click **Register application**.
4. Tick **Enable Device Flow** and save.
5. Copy the **Client ID**. Do **not** generate a client secret; the device flow does not need one.
6. Add it as a repository variable:
   ```sh
   gh variable set GCODE_GITHUB_CLIENT_ID --body "<client id>"
   ```

If your repositories belong to an organization that restricts OAuth Apps, an organization owner
must approve the GCode OAuth App before it can reach those repositories.

---

## 2. Release signing (`v*` tags)

A version tag such as `v0.1.0` triggers the `release` job. It builds a release APK signed with your
key and attaches it to a GitHub Release.

### 2.1 Create the keystore (once)

```sh
keytool -genkeypair -v \
  -keystore gcode-release.jks -storetype PKCS12 \
  -alias gcode -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` asks for a password and your name details. With the PKCS12 format, the key password is
the same as the store password, so use that value for both `KEYSTORE_PASSWORD` and `KEY_PASSWORD`.

> **Back up `gcode-release.jks` and its password somewhere safe (for example a password manager).**
> If you lose them, you cannot publish updates that install over existing copies of the app.
> Never commit the keystore; `.gitignore` and the CI secret scan block `*.jks` and `*.keystore`.

### 2.2 Encode it as base64

| System | Command |
|---|---|
| Linux | `base64 -w 0 gcode-release.jks > keystore.b64` |
| macOS | `base64 -i gcode-release.jks -o keystore.b64` |
| Windows (PowerShell) | `[Convert]::ToBase64String([IO.File]::ReadAllBytes("gcode-release.jks")) \| Out-File -NoNewline keystore.b64` |

### 2.3 Add the four secrets

In the web UI, create `KEYSTORE_BASE64` (paste the contents of `keystore.b64`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` (`gcode` if you used the command above) and `KEY_PASSWORD`.

Or with the GitHub CLI (it prompts for the values you do not pipe in):

```sh
gh secret set KEYSTORE_BASE64 < keystore.b64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body gcode
gh secret set KEY_PASSWORD
```

Then delete `keystore.b64`.

### 2.4 Publish a release

```sh
git tag v0.1.0
git push origin v0.1.0
```

The `release` job runs the normal build and tests first, then signs the APK, checks the signature
with `apksigner`, and creates the release `GCode 0.1.0` with `gcode-0.1.0.apk` attached. If a
secret is missing, the job stops and names the missing secret.

---

## 3. Optional: stable debug signing (`DEBUG_KEYSTORE_BASE64`)

By default, every CI run signs the debug APK with a fresh, random debug key. Android refuses to
install an APK over an existing install that was signed with a different key, so you would have to
uninstall the previous debug build each time. A fixed debug key avoids this:

```sh
keytool -genkeypair \
  -keystore gcode-debug.keystore -storetype PKCS12 \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"
base64 -w 0 gcode-debug.keystore > debug.b64     # macOS: base64 -i gcode-debug.keystore -o debug.b64
gh secret set DEBUG_KEYSTORE_BASE64 < debug.b64
rm debug.b64
```

The alias and passwords must be exactly `androiddebugkey` / `android` / `android`, the standard
debug values that the build expects. This key is for debug builds only; never use it for releases.

**From the app:** for repositories you build with GCode (including GCode itself), open
**Builds › Secrets and signing › Create and save debug key**. GCode generates the same kind of key on
the device (or reuses the one it made before, so every repository shares it), encrypts it with the
repository's public key and saves it as `DEBUG_KEYSTORE_BASE64`. You confirm this every time. The
`gcode-build.yml` workflow that **Set up builds** adds reads the secret into `~/.android/debug.keystore`.

The `versionCode` of CI builds is the workflow run number, so each new debug APK is a valid update
of the previous one.

---

## 4. Local builds

The same values can be provided locally through environment variables or Gradle properties:

```sh
export GCODE_GITHUB_CLIENT_ID=<client id>      # or: -Pgcode.githubClientId=<client id>
./gradlew assembleDebug

# Signed release (optional)
export GCODE_RELEASE_KEYSTORE_FILE=/path/to/gcode-release.jks
export KEYSTORE_PASSWORD=... KEY_ALIAS=gcode KEY_PASSWORD=...
./gradlew assembleRelease
```

Without the release variables, `assembleRelease` produces an unsigned APK.
