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
  focusAt?(x: number, y: number, viewWidth: number, viewHeight: number): Promise<SonyFocusResult>;
  getDiagnostics?(): SonyDiagnosticsSnapshot;
  clearDiagnostics?(): void;
}

export default requireOptionalNativeModule<SonyCameraNativeModule>('SonyCamera');
