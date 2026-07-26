#!/usr/bin/env node
/**
 * Propagates the version in package.json into the native build files.
 *
 * package.json is the single source of truth. `npm version` only updates that file, so
 * without this step ios/SonyCamera.podspec and android/build.gradle drift — which is
 * exactly what happened between 0.1.0 and 0.1.1.
 *
 * Runs automatically from `prepare` and `prepublishOnly`. Pass --check to fail instead
 * of writing, which is what CI uses to catch a manual edit that skipped the sync.
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const check = process.argv.includes('--check');

const { version } = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

if (!/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) {
  console.error(`sync-version: "${version}" is not a valid semver version.`);
  process.exit(1);
}

/** Android versionCode must be a monotonically increasing integer, not a semver string. */
function versionCode(semver) {
  const [major, minor, patch] = semver.split('-')[0].split('.').map(Number);
  return major * 10000 + minor * 100 + patch;
}

const targets = [
  {
    file: 'ios/SonyCamera.podspec',
    edits: [{ pattern: /(s\.version\s*=\s*')[^']*(')/, replacement: `$1${version}$2` }],
  },
  {
    file: 'android/build.gradle',
    edits: [
      { pattern: /(^version\s*=\s*')[^']*(')/m, replacement: `$1${version}$2` },
      { pattern: /(versionCode\s+)\d+/, replacement: `$1${versionCode(version)}` },
      { pattern: /(versionName\s+")[^"]*(")/, replacement: `$1${version}$2` },
    ],
  },
];

const stale = [];

for (const { file, edits } of targets) {
  const absolute = path.join(root, file);
  const original = fs.readFileSync(absolute, 'utf8');
  let updated = original;

  for (const { pattern, replacement } of edits) {
    if (!pattern.test(updated)) {
      console.error(`sync-version: ${file} does not match ${pattern}. Update this script.`);
      process.exit(1);
    }
    updated = updated.replace(pattern, replacement);
  }

  if (updated === original) continue;

  if (check) {
    stale.push(file);
  } else {
    fs.writeFileSync(absolute, updated);
    console.log(`sync-version: ${file} -> ${version}`);
  }
}

if (check && stale.length > 0) {
  console.error(
    `sync-version: ${stale.join(', ')} out of sync with package.json (${version}). ` +
      `Run "npm run sync-version" and commit the result.`
  );
  process.exit(1);
}

if (!check && stale.length === 0) {
  console.log(`sync-version: native build files already at ${version}`);
}
