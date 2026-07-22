const { AndroidConfig, withAndroidManifest, withInfoPlist } = require('expo/config-plugins');

const USB_ATTACHED = 'android.hardware.usb.action.USB_DEVICE_ATTACHED';
const USB_HOST = 'android.hardware.usb.host';

module.exports = function withSonyCamera(config) {
  config = withInfoPlist(config, (configWithInfoPlist) => {
    configWithInfoPlist.modResults.NSCameraUsageDescription ??=
      'This app uses an attached camera for live view and product photography.';
    configWithInfoPlist.modResults.NSLocalNetworkUsageDescription ??=
      'This app discovers and controls Sony cameras connected to your local Wi-Fi network.';
    return configWithInfoPlist;
  });

  return withAndroidManifest(config, (configWithManifest) => {
    const manifest = configWithManifest.modResults.manifest;
    const features = manifest['uses-feature'] ?? [];
    if (!features.some((feature) => feature.$?.['android:name'] === USB_HOST)) {
      features.push({ $: { 'android:name': USB_HOST, 'android:required': 'false' } });
    }
    manifest['uses-feature'] = features;

    const activity = AndroidConfig.Manifest.getMainActivityOrThrow(configWithManifest.modResults);
    const filters = activity['intent-filter'] ?? [];
    if (
      !filters.some((filter) =>
        filter.action?.some((action) => action.$?.['android:name'] === USB_ATTACHED)
      )
    ) {
      filters.push({
        action: [{ $: { 'android:name': USB_ATTACHED } }],
        category: [{ $: { 'android:name': 'android.intent.category.DEFAULT' } }],
      });
    }
    activity['intent-filter'] = filters;

    const metadata = activity['meta-data'] ?? [];
    if (!metadata.some((entry) => entry.$?.['android:name'] === USB_ATTACHED)) {
      metadata.push({
        $: {
          'android:name': USB_ATTACHED,
          'android:resource': '@xml/sony_camera_device_filter',
        },
      });
    }
    activity['meta-data'] = metadata;
    return configWithManifest;
  });
};
