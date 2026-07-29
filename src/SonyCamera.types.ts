import type { StyleProp, ViewStyle } from 'react-native';

export type SonyCameraProtocol =
  'sony_camera_control_ptp2' | 'sony_camera_control_ptp3' | 'sony_scalar_webapi_v1';
export type SonyCameraTransport = 'usb' | 'ptp_ip' | 'scalar_http';
export type SonyConnectionMode = 'usb' | 'wifi_direct' | 'infrastructure_wifi';
export type SonyCameraCertification = 'certified' | 'capability_detected';

/**
 * Shared lifecycle contract for current and planned transports.
 *
 * States that are **not yet emitted** by any current adapter but are reserved
 * for future transports (PTP3, property transfers, movie recording):
 * `candidate_found`, `joining_network`, `recording`, `transferring`.
 */
export type SonyCameraStateName =
  | 'unsupported'
  | 'disconnected'
  | 'discovering'
  | 'candidate_found'
  | 'permission_required'
  | 'joining_network'
  | 'connecting'
  | 'authenticating'
  | 'ready'
  | 'streaming'
  | 'capturing'
  | 'recording'
  | 'transferring'
  | 'reconnecting'
  | 'error';

export type SonyCameraControlCategory =
  | 'connection'
  | 'live_view'
  | 'still_capture'
  | 'exposure'
  | 'focus'
  | 'color'
  | 'lens'
  | 'flash'
  | 'movie'
  | 'media'
  | 'network'
  | 'health'
  | 'advanced';
/**
 * Capability flags derived from the camera's own advertised operation, property,
 * and event codes. Not every flag has a runtime implementation yet.
 *
 * **Reserved for future transports / property-control work:**
 * `manualFocus`, `zoom`, `presets`.
 */
export type SonyCameraFeature =
  | 'liveView'
  | 'stillCapture'
  | 'imageTransfer'
  | 'properties'
  | 'movieRecording'
  | 'mediaBrowser'
  | 'events'
  | 'halfPress'
  | 'touchFocus'
  | 'manualFocus'
  | 'zoom'
  | 'presets';
export type SonyCameraCapabilities = {
  protocols: SonyCameraProtocol[];
  transports: SonyCameraTransport[];
  connectionModes: SonyConnectionMode[];
  categories: SonyCameraControlCategory[];
  features: Partial<Record<SonyCameraFeature, boolean>>;
};
export type SonyCameraPropertyValue = string | number | boolean | null;
export type SonyCameraPropertyOption = { label: string; value: SonyCameraPropertyValue };
export type SonyCameraProperty = {
  code: string;
  key: string | null;
  label: string;
  category: SonyCameraControlCategory;
  valueType: 'boolean' | 'integer' | 'decimal' | 'enum' | 'string' | 'action';
  readable: boolean;
  writable: boolean;
  enabled: boolean;
  currentValue: SonyCameraPropertyValue;
  allowedValues?: SonyCameraPropertyOption[];
  range?: { minimum: number; maximum: number; step: number };
  unit?: string;
  requiresMode?: string[];
  revision: number;
};

export type SonyCameraInfo = {
  vendorId: number;
  productId: number;
  deviceName: string;
  manufacturerName?: string;
  productName?: string;
  model: string;
  firmware?: string;
  protocol: SonyCameraProtocol;
  transport?: SonyCameraTransport;
  connectionMode?: SonyConnectionMode;
  certification?: SonyCameraCertification;
  capabilities?: SonyCameraCapabilities;
};

export type SonyCameraCandidate = {
  id: string;
  model: string;
  protocol: SonyCameraProtocol;
  transport: SonyCameraTransport;
  connectionMode: SonyConnectionMode;
  certification: SonyCameraCertification;
  capabilities: SonyCameraCapabilities;
};

export type SonyCameraState = {
  state: SonyCameraStateName;
  message?: string;
  device?: SonyCameraInfo;
  diagnostics?: string[];
};

export type SonyPhoto = {
  uri: string;
  width: number;
  height: number;
  fileName: string;
  mimeType: 'image/jpeg';
};

export type SonyConnectOptions = {
  candidateId?: string;
  preferredProtocol?: SonyCameraProtocol;
  preferredTransport?: SonyCameraTransport;
};
export type SonyCameraOperationResult = { ok: boolean; message?: string };
export type SonyPropertyMutationResult = SonyCameraOperationResult & {
  property?: SonyCameraProperty;
};
export type SonyPresetApplyResult = SonyCameraOperationResult & {
  applied: SonyPropertyMutationResult[];
  failed: SonyPropertyMutationResult[];
};
export type SonyDiagnosticsSnapshot = {
  entries: string[];
  protocol?: SonyCameraProtocol;
  transport?: SonyCameraTransport;
};
export type SonyFocusResult = {
  status: 'focused' | 'started' | 'failed' | 'unsupported';
  reason?: string;
};

export type SonyCameraModuleEvents = {
  onStateChanged: (state: SonyCameraState) => void;
  onDeviceAttached: (state: SonyCameraState) => void;
  onPhotoCaptured: (photo: SonyPhoto) => void;
};

export type SonyCameraViewProps = {
  active?: boolean;
  style?: StyleProp<ViewStyle>;
};

export type SonyUsbStreamingStateName =
  'unsupported' | 'disconnected' | 'permission_required' | 'ready' | 'streaming' | 'error';

export type SonyUsbStreamingFormat = {
  width: number;
  height: number;
  maxFrameRate: number;
};

export type SonyUsbStreamingDevice = {
  name: string;
  protocol: 'uvc';
  transport: 'usb_uvc';
  formats: SonyUsbStreamingFormat[];
  activeFormat?: SonyUsbStreamingFormat;
};

export type SonyUsbStreamingCapabilities = {
  liveView: boolean;
  previewFrameCapture: boolean;
  audio: boolean;
  remoteShutter: false;
  cameraProperties: false;
};

export type SonyUsbStreamingState = {
  state: SonyUsbStreamingStateName;
  message?: string;
  device?: SonyUsbStreamingDevice;
  /** iOS capability payload. Android telemetry fields below are authoritative there. */
  capabilities?: SonyUsbStreamingCapabilities;
  diagnostics?: string[];
  deviceName?: string | null;
  width?: number;
  height?: number;
  fps?: number;
  frameCount?: number;
  maximumFrameGapMs?: number;
  pixelFormat?: string | null;
  sourceFpsMin?: number | null;
  sourceFpsMax?: number | null;
  audioAvailable?: boolean;
  audioDevices?: string[];
  streamReady?: boolean;
  transport?: 'usb_uvc' | 'android_libuvc_direct' | 'android_camera2_uvc';
};

export type SonyUsbStreamingDiagnostics = {
  entries: string[];
  protocol: 'uvc';
  transport: 'usb_uvc';
};

export type SonyUsbStreamingModuleEvents = {
  onStateChanged: (state: SonyUsbStreamingState) => void;
};

export type SonyUsbStreamingViewProps = {
  active?: boolean;
  style?: StyleProp<ViewStyle>;
};
