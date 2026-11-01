// Points the Android shell at one of the apps in apps/<name>/: writes capacitor.config.json,
// and (after `npx cap add/sync android`) stamps the app id, name and version into the Android project.
// Usage: node scripts/configure.js <app> [--android]
const fs = require('fs');
const path = require('path');

const [name, flag] = process.argv.slice(2);
if (!name) { console.error('usage: node scripts/configure.js <app> [--android]'); process.exit(1); }
const root = path.join(__dirname, '..');
const app = JSON.parse(fs.readFileSync(path.join(root, 'apps', name, 'app.json'), 'utf8'));

if (flag !== '--android') {
  const config = {
    appId: app.appId,
    appName: app.appName,
    webDir: 'www',
    backgroundColor: app.backgroundColor,
    // The app is the live website: push to GitHub Pages and the phone has the new version next launch.
    server: { url: app.url, cleartext: false },
    android: { allowMixedContent: false },
    plugins: { LocalNotifications: { smallIcon: 'ic_stat_notify', iconColor: '#f3c233' } },
  };
  fs.writeFileSync(path.join(root, 'capacitor.config.json'), JSON.stringify(config, null, 2) + '\n');
  // the offline fallback page names the app
  const www = fs.readFileSync(path.join(root, 'www', 'index.template.html'), 'utf8');
  fs.writeFileSync(path.join(root, 'www', 'index.html'), www.replaceAll('{{APP_NAME}}', app.appName).replaceAll('{{APP_URL}}', app.url));
  console.log(`configured ${app.appName} → ${app.url}`);
  process.exit(0);
}

// --android: patch the generated Android project
const gradle = path.join(root, 'android', 'app', 'build.gradle');
const run = Number(process.env.GITHUB_RUN_NUMBER || 1);
let g = fs.readFileSync(gradle, 'utf8');
g = g.replace(/applicationId "[^"]*"/, `applicationId "${app.appId}"`)
     .replace(/versionCode \d+/, `versionCode ${run}`)
     .replace(/versionName "[^"]*"/, `versionName "1.${run}"`);
fs.writeFileSync(gradle, g);

const strings = path.join(root, 'android', 'app', 'src', 'main', 'res', 'values', 'strings.xml');
let s = fs.readFileSync(strings, 'utf8');
s = s.replace(/(<string name="app_name">)[^<]*/, `$1${app.appName}`)
     .replace(/(<string name="title_activity_main">)[^<]*/, `$1${app.appName}`);
fs.writeFileSync(strings, s);
console.log(`android project stamped: ${app.appId} v1.${run}`);
