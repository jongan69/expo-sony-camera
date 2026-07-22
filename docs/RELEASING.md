# Releasing

## Local release

1. Confirm the version in `package.json` and `CHANGELOG.md`.
2. Run the package and example checks:

   ```bash
   npm ci
   npm run build
   npm run lint
   npm test
   npm pack --dry-run
   npm --prefix example ci
   npm --prefix example run typecheck
   ```

3. Inspect the tarball contents. The example app and native test sources should not be
   included in the published payload.
4. Publish from an authenticated account:

   ```bash
   npm whoami
   npm publish --access public
   ```

5. Verify the registry result:

   ```bash
   npm view expo-sony-camera version
   npm view expo-sony-camera dist.tarball
   ```

## GitHub release

- `main` is the release branch.
- `.github/workflows/ci.yml` validates package and example changes.
- `.github/workflows/publish.yml` is a manual publish path with NPM provenance.
- Create a Git tag matching the NPM version after publication, for example `v0.1.0`.
- Do not claim physical camera certification in a release note unless the hardware
  matrix has been completed.

## Versioning policy

- Patch: documentation, tests, or backward-compatible fixes.
- Minor: new backward-compatible capability or supported transport.
- Major: breaking JavaScript contract, native lifecycle, or return-shape change.

Every release must state supported protocols, unsupported protocols, known device/OS
limits, validation evidence, and whether the change is source, build, physical, or
registry evidence.

## First publish note

The initial public package is intentionally `0.1.0` and experimental. PTP3 is roadmap
work, not part of this release.
