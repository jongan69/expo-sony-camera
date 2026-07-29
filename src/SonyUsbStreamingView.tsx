import { requireNativeView } from 'expo';
import * as React from 'react';

import type { SonyUsbStreamingViewProps } from './SonyCamera.types';

const NativeView: React.ComponentType<SonyUsbStreamingViewProps> =
  requireNativeView('SonyUsbStreaming');

export default function SonyUsbStreamingView(props: SonyUsbStreamingViewProps) {
  return <NativeView {...props} />;
}
