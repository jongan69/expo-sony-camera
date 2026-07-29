import * as React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import type { SonyUsbStreamingViewProps } from './SonyCamera.types';

export default function SonyUsbStreamingView({ style }: SonyUsbStreamingViewProps) {
  return (
    <View style={[styles.container, style]}>
      <Text style={styles.text}>Sony USB Streaming requires a native Android or iOS device.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { alignItems: 'center', backgroundColor: '#071019', justifyContent: 'center' },
  text: { color: '#fff', padding: 16, textAlign: 'center' },
});
