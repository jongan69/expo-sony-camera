import { NativeModule, requireOptionalNativeModule } from 'expo';

import type {
  SonyPhoto,
  SonyUsbStreamingDiagnostics,
  SonyUsbStreamingModuleEvents,
  SonyUsbStreamingState,
} from './SonyCamera.types';

declare class SonyUsbStreamingNativeModule extends NativeModule<SonyUsbStreamingModuleEvents> {
  getState(): SonyUsbStreamingState;
  getDiagnostics(): SonyUsbStreamingDiagnostics;
  clearDiagnostics(): void;
  startStreaming(): Promise<SonyUsbStreamingState>;
  stopStreaming(): Promise<SonyUsbStreamingState>;
  /** Implemented on iOS. Android callers must treat this as unsupported in 0.2.0. */
  capturePreviewFrame(): Promise<SonyPhoto>;
}

export default requireOptionalNativeModule<SonyUsbStreamingNativeModule>('SonyUsbStreaming');
