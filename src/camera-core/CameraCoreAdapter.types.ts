export type CameraCorePluginId = string;
export type CameraCoreCandidateId = string;
export type CameraCoreSessionId = string;

export type CameraCoreConnectionMode = 'usb' | 'wifi_direct' | 'infrastructure_wifi';
export type CameraCoreTransportKind = 'usb' | 'ip' | 'http';

export type CameraCoreSessionState =
  | 'disconnected'
  | 'permission_required'
  | 'connecting'
  | 'authenticating'
  | 'ready'
  | 'previewing'
  | 'capturing'
  | 'recording'
  | 'transferring'
  | 'reconnecting'
  | 'disconnecting'
  | 'error';

export type CameraCorePluginDescriptor = {
  pluginId: CameraCorePluginId;
  apiVersion: 1;
  displayName: string;
  providerKind: 'remote_camera';
  supportedPlatforms: ('android' | 'ios')[];
  connectionModes: CameraCoreConnectionMode[];
  priority: number;
  certification: 'built_in' | 'experimental' | 'disabled';
};

export type CameraCoreCapabilities = {
  revision: number;
  preview: {
    supported: boolean;
    formats: ('native_surface' | 'jpeg_stream')[];
    touchFocus: boolean;
    sampledFrames: boolean;
  };
  stillCapture: {
    supported: boolean;
    halfPress: boolean;
    bulb: boolean;
    selfTimer: boolean;
    burst: boolean;
    bracketing: boolean;
  };
  video: {
    supported: boolean;
    remoteStartStop: boolean;
  };
  focus: {
    autoFocus: boolean;
    manualFocus: boolean;
    focusArea: boolean;
    subjectSelection: boolean;
  };
  properties: {
    readable: boolean;
    writable: boolean;
    presets: boolean;
  };
  media: {
    browse: boolean;
    download: boolean;
    cancelDownload: boolean;
  };
  health: {
    battery: boolean;
    storage: boolean;
    temperature: boolean;
  };
};

export type CameraCoreCandidate = {
  candidateId: CameraCoreCandidateId;
  pluginId: CameraCorePluginId;
  displayName: string;
  connectionMode: CameraCoreConnectionMode;
  transport: CameraCoreTransportKind;
  connectionState: 'available' | 'permission_required' | 'unavailable';
  setupHint?: string;
  capabilityHints: Partial<CameraCoreCapabilities>;
};

export type CameraCorePluginSessionMetadata = {
  sessionId: CameraCoreSessionId;
  candidateId: CameraCoreCandidateId;
  pluginId: CameraCorePluginId;
  state: CameraCoreSessionState;
  connectedAt: string;
};

export type CameraCoreCapturedAsset = {
  assetId: string;
  sessionId: CameraCoreSessionId;
  localUri: string;
  mediaType: 'jpeg';
  mimeType: 'image/jpeg';
  width: number;
  height: number;
  capturedAt: string;
  source: 'direct_capture' | 'preview_frame';
  listingEligible: boolean;
};

export type CameraCoreErrorCode =
  | 'plugin_unavailable'
  | 'permission_denied'
  | 'candidate_not_found'
  | 'connection_failed'
  | 'authentication_failed'
  | 'connection_lost'
  | 'operation_unsupported'
  | 'operation_busy'
  | 'operation_timeout'
  | 'operation_state_unknown'
  | 'preview_failed'
  | 'capture_failed'
  | 'recording_failed'
  | 'transfer_failed'
  | 'storage_full'
  | 'canceled'
  | 'internal_adapter_error';

export type CameraCoreError = {
  code: CameraCoreErrorCode;
  message: string;
  retryable: boolean;
  operationStateKnown: boolean;
  recoveryAction:
    | 'retry'
    | 'reconnect'
    | 'request_permission'
    | 'change_camera_mode'
    | 'free_storage'
    | 'choose_another_provider'
    | 'contact_support'
    | 'none';
};

export type CameraCoreCommand =
  | { type: 'start_preview'; operationId: string }
  | { type: 'stop_preview'; operationId: string }
  | {
      type: 'focus';
      operationId: string;
      point: { x: number; y: number };
      mode: 'auto' | 'tracking';
    }
  | { type: 'capture_photo'; operationId: string }
  | { type: 'capture_preview_frame'; operationId: string }
  | { type: 'start_recording'; operationId: string }
  | { type: 'stop_recording'; operationId: string }
  | { type: 'get_properties'; operationId: string }
  | { type: 'set_property'; operationId: string; propertyId: string; value: unknown }
  | { type: 'apply_preset'; operationId: string; changes: unknown[] }
  | { type: 'list_media'; operationId: string }
  | { type: 'download_media'; operationId: string; mediaId: string }
  | { type: 'get_diagnostics'; operationId: string }
  | { type: 'clear_diagnostics'; operationId: string };

export type CameraCoreCommandResult =
  | {
      ok: true;
      commandType: CameraCoreCommand['type'];
      state?: CameraCoreSessionState;
      asset?: CameraCoreCapturedAsset;
      focus?: { status: 'focused' | 'started' };
      diagnostics?: { entries: string[]; adapter?: string; transport?: string };
    }
  | {
      ok: false;
      commandType: CameraCoreCommand['type'];
      error: CameraCoreError;
    };

export type CameraCoreEventType =
  | 'camera.device.attached.v1'
  | 'camera.session.state_changed.v1'
  | 'camera.preview.started.v1'
  | 'camera.preview.stopped.v1'
  | 'camera.focus.completed.v1'
  | 'camera.capture.completed.v1'
  | 'camera.operation.failed.v1';

export type CameraCoreEvent = {
  eventId: string;
  eventType: CameraCoreEventType;
  eventVersion: 1;
  sequence: number;
  occurredAt: string;
  sessionId?: CameraCoreSessionId;
  operationId?: string;
  correlationId: string;
  payload: Record<string, unknown>;
};

export type CameraCoreEventListener = (event: CameraCoreEvent) => void;
export type CameraCoreUnsubscribe = () => void;

export type CameraCoreDiscoveryRequest = { timeoutMs?: number };
export type CameraCoreConnectRequest = { candidateId?: CameraCoreCandidateId };

export interface CameraCorePluginSession {
  sessionMetadata(): CameraCorePluginSessionMetadata;
  capabilities(): Promise<CameraCoreCapabilities>;
  execute(command: CameraCoreCommand, signal?: AbortSignal): Promise<CameraCoreCommandResult>;
  subscribe(listener: CameraCoreEventListener): CameraCoreUnsubscribe;
  close(): Promise<void>;
}

export interface CameraCorePlugin {
  descriptor(): CameraCorePluginDescriptor;
  isAvailable(): Promise<boolean>;
  discover(request?: CameraCoreDiscoveryRequest): Promise<CameraCoreCandidate[]>;
  connect(request?: CameraCoreConnectRequest): Promise<CameraCorePluginSession>;
}
