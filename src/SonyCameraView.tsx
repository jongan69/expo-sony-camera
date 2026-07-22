import { requireNativeView } from 'expo';
import * as React from 'react';

import type { SonyCameraViewProps } from './SonyCamera.types';

const NativeView: React.ComponentType<SonyCameraViewProps> = requireNativeView('SonyCamera');

export default function SonyCameraView(props: SonyCameraViewProps) {
  return <NativeView {...props} />;
}
