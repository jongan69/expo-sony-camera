import SonyCamera from '../SonyCameraModule';
import type {
  SonyCameraCapabilities,
  SonyCameraInfo,
  SonyCameraModuleEvents,
  SonyCameraState,
  SonyFocusResult,
  SonyPhoto,
} from '../SonyCamera.types';
import type {
  CameraCoreCandidate,
  CameraCoreCapabilities,
  CameraCoreCommand,
  CameraCoreCommandResult,
  CameraCoreConnectRequest,
  CameraCoreError,
  CameraCoreEvent,
  CameraCoreEventListener,
  CameraCoreEventType,
  CameraCorePlugin,
  CameraCorePluginDescriptor,
  CameraCorePluginSession,
  CameraCorePluginSessionMetadata,
  CameraCoreSessionState,
  CameraCoreUnsubscribe,
} from './CameraCoreAdapter.types';

const PLUGIN_ID = 'camera.sony.remote';

type NativeSubscription = { remove(): void };

export interface SonyCameraNativePort {
  getState(): SonyCameraState;
  connect(options?: Record<string, unknown>): Promise<SonyCameraState>;
  disconnect(): Promise<SonyCameraState>;
  startLiveView(): Promise<SonyCameraState>;
  stopLiveView(): Promise<SonyCameraState>;
  capturePhoto(): Promise<SonyPhoto>;
  capturePreviewFrame(): Promise<SonyPhoto>;
  focusAt?(x: number, y: number, viewWidth: number, viewHeight: number): Promise<SonyFocusResult>;
  getDiagnostics(): { entries: string[]; protocol?: string; transport?: string };
  clearDiagnostics(): void;
  addListener<EventName extends keyof SonyCameraModuleEvents>(
    eventName: EventName,
    listener: SonyCameraModuleEvents[EventName]
  ): NativeSubscription;
}

let nextSessionSequence = 0;
let nextEventSequence = 0;

function makeId(prefix: string): string {
  const sequence = prefix === 'camera-session' ? ++nextSessionSequence : ++nextEventSequence;
  return `${prefix}-${Date.now()}-${sequence}`;
}

function defaultCapabilities(revision = 1): CameraCoreCapabilities {
  return {
    revision,
    preview: { supported: false, formats: [], touchFocus: false, sampledFrames: false },
    stillCapture: {
      supported: false,
      halfPress: false,
      bulb: false,
      selfTimer: false,
      burst: false,
      bracketing: false,
    },
    video: { supported: false, remoteStartStop: false },
    focus: {
      autoFocus: false,
      manualFocus: false,
      focusArea: false,
      subjectSelection: false,
    },
    properties: { readable: false, writable: false, presets: false },
    media: { browse: false, download: false, cancelDownload: false },
    health: { battery: false, storage: false, temperature: false },
  };
}

export function mapSonyCapabilities(
  capabilities: SonyCameraCapabilities | undefined,
  hasCoordinateFocus: boolean,
  revision = 1
): CameraCoreCapabilities {
  const mapped = defaultCapabilities(revision);
  const features = capabilities?.features ?? {};
  const previewSupported = features.liveView === true;
  const captureSupported = features.stillCapture === true;
  const touchFocusSupported = features.touchFocus === true && hasCoordinateFocus;

  mapped.preview = {
    supported: previewSupported,
    formats: previewSupported ? ['native_surface', 'jpeg_stream'] : [],
    touchFocus: touchFocusSupported,
    sampledFrames: previewSupported,
  };
  mapped.stillCapture.supported = captureSupported;
  mapped.stillCapture.halfPress = features.halfPress === true;
  mapped.focus.autoFocus = captureSupported || features.halfPress === true;
  mapped.focus.focusArea = touchFocusSupported;

  // The Sony native bridge does not yet expose generic property, recording,
  // media-browser, or health APIs. Advertised protocol support alone must not
  // be promoted to a callable Camera Core capability.
  return mapped;
}

export function mapSonyState(state: SonyCameraState['state']): CameraCoreSessionState {
  switch (state) {
    case 'disconnected':
      return 'disconnected';
    case 'permission_required':
      return 'permission_required';
    case 'connecting':
    case 'discovering':
    case 'candidate_found':
    case 'joining_network':
      return 'connecting';
    case 'authenticating':
      return 'authenticating';
    case 'ready':
      return 'ready';
    case 'streaming':
      return 'previewing';
    case 'capturing':
      return 'capturing';
    case 'recording':
      return 'recording';
    case 'transferring':
      return 'transferring';
    case 'reconnecting':
      return 'reconnecting';
    case 'unsupported':
    case 'error':
      return 'error';
  }
}

function candidateId(device: SonyCameraInfo): string {
  return `${PLUGIN_ID}:${device.protocol}:${device.transport ?? 'unknown'}`;
}

function mapTransport(device: SonyCameraInfo): CameraCoreCandidate['transport'] {
  if (device.transport === 'ptp_ip') return 'ip';
  if (device.transport === 'scalar_http') return 'http';
  return 'usb';
}

function mapConnectionMode(device: SonyCameraInfo): CameraCoreCandidate['connectionMode'] {
  return device.connectionMode ?? 'usb';
}

export function mapSonyCandidate(
  device: SonyCameraInfo,
  hasCoordinateFocus: boolean
): CameraCoreCandidate {
  return {
    candidateId: candidateId(device),
    pluginId: PLUGIN_ID,
    displayName: device.model || device.productName || 'Sony camera',
    connectionMode: mapConnectionMode(device),
    transport: mapTransport(device),
    connectionState: 'available',
    setupHint: 'Camera selection is automatic in the current Sony native bridge.',
    capabilityHints: mapSonyCapabilities(device.capabilities, hasCoordinateFocus),
  };
}

export function normalizeSonyError(
  error: unknown,
  operation: CameraCoreCommand['type'] | 'connect'
): CameraCoreError {
  const message = error instanceof Error ? error.message : String(error);
  const normalized = message.toLowerCase();

  if (normalized.includes('permission') || normalized.includes('access denied')) {
    return {
      code: 'permission_denied',
      message,
      retryable: true,
      operationStateKnown: true,
      recoveryAction: 'request_permission',
    };
  }
  if (normalized.includes('not found') || normalized.includes('no camera')) {
    return {
      code: 'candidate_not_found',
      message,
      retryable: true,
      operationStateKnown: true,
      recoveryAction: 'choose_another_provider',
    };
  }
  if (normalized.includes('busy')) {
    return {
      code: 'operation_busy',
      message,
      retryable: true,
      operationStateKnown: true,
      recoveryAction: 'retry',
    };
  }
  if (normalized.includes('timeout') || normalized.includes('timed out')) {
    return {
      code: 'operation_timeout',
      message,
      retryable: true,
      operationStateKnown: operation !== 'capture_photo' && operation !== 'start_recording',
      recoveryAction: 'retry',
    };
  }
  if (
    normalized.includes('disconnect') ||
    normalized.includes('connection lost') ||
    normalized.includes('eof')
  ) {
    return {
      code: 'connection_lost',
      message,
      retryable: true,
      operationStateKnown: operation !== 'capture_photo' && operation !== 'start_recording',
      recoveryAction: 'reconnect',
    };
  }
  if (normalized.includes('unsupported')) {
    return {
      code: 'operation_unsupported',
      message,
      retryable: false,
      operationStateKnown: true,
      recoveryAction: 'none',
    };
  }
  if (normalized.includes('storage') || normalized.includes('no space')) {
    return {
      code: 'storage_full',
      message,
      retryable: true,
      operationStateKnown: true,
      recoveryAction: 'free_storage',
    };
  }

  return {
    code:
      operation === 'connect'
        ? 'connection_failed'
        : operation === 'capture_photo' || operation === 'capture_preview_frame'
          ? 'capture_failed'
          : operation === 'start_recording' || operation === 'stop_recording'
            ? 'recording_failed'
            : operation === 'start_preview' || operation === 'stop_preview'
              ? 'preview_failed'
              : 'internal_adapter_error',
    message,
    retryable: false,
    operationStateKnown: operation !== 'capture_photo' && operation !== 'start_recording',
    recoveryAction: 'contact_support',
  };
}

function unsupported(command: CameraCoreCommand): CameraCoreCommandResult {
  return {
    ok: false,
    commandType: command.type,
    error: {
      code: 'operation_unsupported',
      message: `${command.type} is not exposed by the current Sony native bridge.`,
      retryable: false,
      operationStateKnown: true,
      recoveryAction: 'none',
    },
  };
}

class SonyCameraCoreSession implements CameraCorePluginSession {
  private readonly listeners = new Set<CameraCoreEventListener>();
  private readonly nativeSubscriptions: NativeSubscription[];
  private readonly connectedAt = new Date().toISOString();
  private readonly sessionId = makeId('camera-session');
  private state: CameraCoreSessionState;
  private closed = false;

  constructor(
    private readonly native: SonyCameraNativePort,
    private readonly candidate: CameraCoreCandidate,
    initialState: SonyCameraState,
    private readonly onClosed: () => void
  ) {
    this.state = mapSonyState(initialState.state);
    this.nativeSubscriptions = [
      native.addListener('onStateChanged', (nextState) => {
        this.state = mapSonyState(nextState.state);
        this.emit('camera.session.state_changed.v1', {
          state: this.state,
          message: nextState.message,
        });
      }),
      native.addListener('onDeviceAttached', (nextState) => {
        this.emit('camera.device.attached.v1', {
          state: mapSonyState(nextState.state),
          candidateId: nextState.device ? candidateId(nextState.device) : this.candidate.candidateId,
        });
      }),
    ];
  }

  sessionMetadata(): CameraCorePluginSessionMetadata {
    return {
      sessionId: this.sessionId,
      candidateId: this.candidate.candidateId,
      pluginId: PLUGIN_ID,
      state: this.state,
      connectedAt: this.connectedAt,
    };
  }

  async capabilities(): Promise<CameraCoreCapabilities> {
    const state = this.native.getState();
    return mapSonyCapabilities(state.device?.capabilities, typeof this.native.focusAt === 'function');
  }

  subscribe(listener: CameraCoreEventListener): CameraCoreUnsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async execute(
    command: CameraCoreCommand,
    signal?: AbortSignal
  ): Promise<CameraCoreCommandResult> {
    if (signal?.aborted) return this.canceled(command);
    if (this.closed) {
      return {
        ok: false,
        commandType: command.type,
        error: {
          code: 'connection_lost',
          message: 'The Sony camera session is closed.',
          retryable: true,
          operationStateKnown: true,
          recoveryAction: 'reconnect',
        },
      };
    }

    try {
      switch (command.type) {
        case 'start_preview': {
          const state = await this.native.startLiveView();
          this.state = mapSonyState(state.state);
          this.emit('camera.preview.started.v1', {}, command.operationId);
          return { ok: true, commandType: command.type, state: this.state };
        }
        case 'stop_preview': {
          const state = await this.native.stopLiveView();
          this.state = mapSonyState(state.state);
          this.emit('camera.preview.stopped.v1', {}, command.operationId);
          return { ok: true, commandType: command.type, state: this.state };
        }
        case 'focus': {
          if (!this.native.focusAt) return unsupported(command);
          if (command.point.x < 0 || command.point.x > 1 || command.point.y < 0 || command.point.y > 1) {
            return {
              ok: false,
              commandType: command.type,
              error: {
                code: 'internal_adapter_error',
                message: 'Focus coordinates must be normalized between 0 and 1.',
                retryable: false,
                operationStateKnown: true,
                recoveryAction: 'none',
              },
            };
          }
          const result = await this.native.focusAt(command.point.x, command.point.y, 1, 1);
          if (result.status === 'unsupported') return unsupported(command);
          if (result.status === 'failed') throw new Error(result.reason ?? 'Sony focus failed.');
          this.emit('camera.focus.completed.v1', { status: result.status }, command.operationId);
          return { ok: true, commandType: command.type, focus: { status: result.status } };
        }
        case 'capture_photo':
          return this.capture(command, false);
        case 'capture_preview_frame':
          return this.capture(command, true);
        case 'get_diagnostics': {
          const diagnostics = this.native.getDiagnostics();
          return {
            ok: true,
            commandType: command.type,
            diagnostics: {
              entries: diagnostics.entries,
              adapter: diagnostics.protocol,
              transport: diagnostics.transport,
            },
          };
        }
        case 'clear_diagnostics':
          this.native.clearDiagnostics();
          return { ok: true, commandType: command.type };
        case 'start_recording':
        case 'stop_recording':
        case 'get_properties':
        case 'set_property':
        case 'apply_preset':
        case 'list_media':
        case 'download_media':
          return unsupported(command);
      }
    } catch (error) {
      const normalized = normalizeSonyError(error, command.type);
      this.emit(
        'camera.operation.failed.v1',
        { commandType: command.type, error: normalized },
        command.operationId
      );
      return { ok: false, commandType: command.type, error: normalized };
    }
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    try {
      const state = await this.native.disconnect();
      this.state = mapSonyState(state.state);
    } finally {
      for (const subscription of this.nativeSubscriptions) subscription.remove();
      this.nativeSubscriptions.length = 0;
      this.listeners.clear();
      this.onClosed();
    }
  }

  private async capture(
    command: Extract<CameraCoreCommand, { type: 'capture_photo' | 'capture_preview_frame' }>,
    previewFrame: boolean
  ): Promise<CameraCoreCommandResult> {
    const photo = previewFrame
      ? await this.native.capturePreviewFrame()
      : await this.native.capturePhoto();
    const capturedAt = new Date().toISOString();
    const asset = {
      assetId: `${command.operationId}:${photo.fileName}`,
      sessionId: this.sessionId,
      localUri: photo.uri,
      mediaType: 'jpeg' as const,
      mimeType: photo.mimeType,
      width: photo.width,
      height: photo.height,
      capturedAt,
      source: previewFrame ? ('preview_frame' as const) : ('direct_capture' as const),
      listingEligible: !previewFrame,
    };
    this.emit('camera.capture.completed.v1', { asset }, command.operationId);
    return { ok: true, commandType: command.type, asset };
  }

  private canceled(command: CameraCoreCommand): CameraCoreCommandResult {
    return {
      ok: false,
      commandType: command.type,
      error: {
        code: 'canceled',
        message: 'The camera operation was canceled before it started.',
        retryable: false,
        operationStateKnown: true,
        recoveryAction: 'none',
      },
    };
  }

  private emit(
    eventType: CameraCoreEventType,
    payload: Record<string, unknown>,
    operationId?: string
  ): void {
    const event: CameraCoreEvent = {
      eventId: makeId('camera-event'),
      eventType,
      eventVersion: 1,
      sequence: nextEventSequence,
      occurredAt: new Date().toISOString(),
      sessionId: this.sessionId,
      operationId,
      correlationId: operationId ?? this.sessionId,
      payload,
    };
    for (const listener of this.listeners) listener(event);
  }
}

export class SonyCameraCorePlugin implements CameraCorePlugin {
  private activeSession: SonyCameraCoreSession | null = null;

  constructor(private readonly native: SonyCameraNativePort | null = SonyCamera) {}

  descriptor(): CameraCorePluginDescriptor {
    return {
      pluginId: PLUGIN_ID,
      apiVersion: 1,
      displayName: 'Sony remote camera',
      providerKind: 'remote_camera',
      supportedPlatforms: ['android', 'ios'],
      connectionModes: ['usb', 'wifi_direct', 'infrastructure_wifi'],
      priority: 100,
      certification: 'experimental',
    };
  }

  async isAvailable(): Promise<boolean> {
    return this.native !== null;
  }

  async discover(): Promise<CameraCoreCandidate[]> {
    if (!this.native) return [];
    const state = this.native.getState();
    if (!state.device) return [];
    return [mapSonyCandidate(state.device, typeof this.native.focusAt === 'function')];
  }

  async connect(request: CameraCoreConnectRequest = {}): Promise<CameraCorePluginSession> {
    if (!this.native) {
      throw new Error('Sony camera plug-in is unavailable in this build.');
    }
    if (this.activeSession) return this.activeSession;

    // Candidate selection is not yet honored by the native bridge. The request
    // is accepted for Camera Core compatibility, while native auto-selection
    // remains authoritative during the migration window.
    const state = await this.native.connect();
    if (!state.device) {
      throw new Error('Sony camera connected without a device descriptor.');
    }
    const candidate = mapSonyCandidate(state.device, typeof this.native.focusAt === 'function');
    if (request.candidateId && request.candidateId !== candidate.candidateId) {
      await this.native.disconnect();
      throw new Error('The connected Sony camera does not match the requested candidate.');
    }
    const session = new SonyCameraCoreSession(this.native, candidate, state, () => {
      if (this.activeSession === session) this.activeSession = null;
    });
    this.activeSession = session;
    return session;
  }
}

export function createSonyCameraCorePlugin(
  native?: SonyCameraNativePort | null
): SonyCameraCorePlugin {
  return new SonyCameraCorePlugin(native === undefined ? SonyCamera : native);
}
