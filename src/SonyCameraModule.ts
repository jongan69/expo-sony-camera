import { NativeModule, requireOptionalNativeModule } from 'expo';

import type {
  SonyCameraModuleEvents,
  SonyConnectOptions,
  SonyDiagnosticsSnapshot,
  SonyFocusResult,
  SonyCameraState,
  SonyPhoto,
} from './SonyCamera.types';

declare class SonyCameraNativeModule extends NativeModule<SonyCameraModuleEvents> {
  getState(): SonyCameraState;
  connect(options?: SonyConnectOptions): Promise<SonyCameraState>;
  disconnect(): Promise<SonyCameraState>;
  startLiveView(): Promise<SonyCameraState>;
  stopLiveView(): Promise<SonyCameraState>;
  capturePhoto(): Promise<SonyPhoto>;
  /**
   * Persists the most recent live-view frame without triggering the shutter.
   *
   * This is a preview-resolution still, not a full capture, and it rejects when no frame
   * has arrived yet. It does not emit `onPhotoCaptured`.
   */
  capturePreviewFrame(): Promise<SonyPhoto>;
  /** Android only, and only when the active ScalarWebAPI camera advertises coordinate focus. */
  focusAt?(x: number, y: number, viewWidth: number, viewHeight: number): Promise<SonyFocusResult>;
  getDiagnostics(): SonyDiagnosticsSnapshot;
  clearDiagnostics(): void;
}

export default requireOptionalNativeModule<SonyCameraNativeModule>('SonyCamera');
