import SonyCamera, {
  SonyCameraView,
  SonyUsbStreaming,
  SonyUsbStreamingView,
} from 'expo-sony-camera';
import { useEffect, useRef, useState } from 'react';
import { Button, Platform, SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { SonyCameraState, SonyUsbStreamingState } from 'expo-sony-camera';

function isConnectedState(state: SonyCameraState['state']) {
  return ['ready', 'streaming', 'capturing', 'transferring', 'recording'].includes(state);
}

function describeError(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

type CertificationResult = {
  name: string;
  status: 'running' | 'passed' | 'failed';
  detail: string;
  elapsedMs: number;
};

function wait(milliseconds: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, milliseconds));
}

export default function App() {
  const nativeCamera = SonyCamera;
  const [connected, setConnected] = useState(false);
  const [previewActive, setPreviewActive] = useState(false);
  const [photoUri, setPhotoUri] = useState<string | null>(null);
  const [state, setState] = useState<SonyCameraState | null>(null);
  const [operation, setOperation] = useState<string | null>(null);
  const [lastError, setLastError] = useState<string | null>(null);
  const [diagnostics, setDiagnostics] = useState<string[]>([]);
  const [usbState, setUsbState] = useState<SonyUsbStreamingState | null>(null);
  const [usbPreviewActive, setUsbPreviewActive] = useState(false);
  const [usbDiagnostics, setUsbDiagnostics] = useState<string[]>([]);
  const [usbPhotoUri, setUsbPhotoUri] = useState<string | null>(null);
  const [certificationRunning, setCertificationRunning] = useState(false);
  const [certificationResults, setCertificationResults] = useState<CertificationResult[]>([]);
  const eventCounts = useRef({ state: 0, attached: 0, photo: 0 });

  useEffect(() => {
    if (!nativeCamera) return;
    const initialState = nativeCamera.getState();
    const initialDiagnostics = nativeCamera.getDiagnostics();
    setState(initialState);
    setConnected(isConnectedState(initialState.state));
    setDiagnostics(initialDiagnostics.entries);
    console.log('[SonyCamera][state:initial]', JSON.stringify(initialState));
    console.log('[SonyCamera][diagnostics:initial]', JSON.stringify(initialDiagnostics));

    const stateSubscription = nativeCamera.addListener('onStateChanged', (nextState) => {
      eventCounts.current.state += 1;
      const snapshot = nativeCamera.getDiagnostics();
      setState(nextState);
      setConnected(isConnectedState(nextState.state));
      setDiagnostics(snapshot.entries);
      console.log('[SonyCamera][state]', JSON.stringify(nextState));
      console.log('[SonyCamera][diagnostics:state]', JSON.stringify(snapshot));
    });
    const attachSubscription = nativeCamera.addListener('onDeviceAttached', (nextState) => {
      eventCounts.current.attached += 1;
      console.log('[SonyCamera][device-attached]', JSON.stringify(nextState));
    });
    const captureSubscription = nativeCamera.addListener('onPhotoCaptured', (photo) => {
      eventCounts.current.photo += 1;
      console.log('[SonyCamera][photo-captured]', JSON.stringify(photo));
    });
    return () => {
      stateSubscription.remove();
      attachSubscription.remove();
      captureSubscription.remove();
    };
  }, [nativeCamera]);

  useEffect(() => {
    const usbStreaming = SonyUsbStreaming;
    if (!usbStreaming) return;
    const initialState = usbStreaming.getState();
    const initialDiagnostics = usbStreaming.getDiagnostics();
    setUsbState(initialState);
    setUsbDiagnostics(initialDiagnostics.entries);
    console.log('[SonyUsbStreaming][state:initial]', JSON.stringify(initialState));
    console.log('[SonyUsbStreaming][diagnostics:initial]', JSON.stringify(initialDiagnostics));
    const subscription = usbStreaming.addListener('onStateChanged', (nextState) => {
      const snapshot = usbStreaming.getDiagnostics();
      setUsbState(nextState);
      setUsbDiagnostics(snapshot.entries);
      setUsbPreviewActive(nextState.state === 'streaming');
      console.log('[SonyUsbStreaming][state]', JSON.stringify(nextState));
      console.log('[SonyUsbStreaming][diagnostics:state]', JSON.stringify(snapshot));
    });
    return () => subscription.remove();
  }, []);

  if (!nativeCamera) {
    return (
      <SafeAreaView style={styles.container}>
        <Text>Native Sony camera support is unavailable on web.</Text>
      </SafeAreaView>
    );
  }

  const camera = nativeCamera;

  function refreshDiagnostics(label: string) {
    const snapshot = camera.getDiagnostics();
    setDiagnostics(snapshot.entries);
    console.log(`[SonyCamera][diagnostics:${label}]`, JSON.stringify(snapshot));
  }

  async function runOperation<T>(name: string, task: () => Promise<T>): Promise<T | null> {
    setOperation(name);
    setLastError(null);
    console.log(`[SonyCamera][operation:${name}] started`);
    try {
      const result = await task();
      console.log(`[SonyCamera][operation:${name}] completed`, JSON.stringify(result));
      refreshDiagnostics(name);
      return result;
    } catch (error) {
      const message = describeError(error);
      setLastError(message);
      console.error(`[SonyCamera][operation:${name}] failed`, message);
      refreshDiagnostics(`${name}:failed`);
      return null;
    } finally {
      setOperation(null);
    }
  }

  async function connect() {
    const next = await runOperation('connect', () =>
      Platform.OS === 'ios'
        ? camera.connect({
            preferredProtocol: 'sony_camera_control_ptp2',
            preferredTransport: 'usb',
          })
        : camera.connect()
    );
    if (!next) return;
    setState(next);
    setConnected(isConnectedState(next.state));
  }

  async function capture() {
    const photo = await runOperation('capture-photo', () => camera.capturePhoto());
    if (photo) setPhotoUri(photo.uri);
  }

  async function disconnect() {
    const next = await runOperation('disconnect', () => camera.disconnect());
    if (!next) return;
    setState(next);
    setConnected(false);
    setPreviewActive(false);
  }

  async function toggleLiveView() {
    if (previewActive) {
      const next = await runOperation('stop-live-view', () => camera.stopLiveView());
      if (next) {
        setState(next);
        setPreviewActive(false);
      }
      return;
    }
    const next = await runOperation('start-live-view', () => camera.startLiveView());
    if (next) {
      setState(next);
      setPreviewActive(next.state === 'streaming');
    }
  }

  function refreshUsbDiagnostics(label: string) {
    if (!SonyUsbStreaming) return;
    const snapshot = SonyUsbStreaming.getDiagnostics();
    setUsbDiagnostics(snapshot.entries);
    console.log(`[SonyUsbStreaming][diagnostics:${label}]`, JSON.stringify(snapshot));
  }

  async function startUsbStreaming() {
    if (!SonyUsbStreaming) return;
    setOperation('start-usb-streaming');
    setLastError(null);
    console.log('[SonyUsbStreaming][operation:start] started');
    try {
      const next = await SonyUsbStreaming.startStreaming();
      setUsbState(next);
      setUsbPreviewActive(next.state === 'streaming');
      console.log('[SonyUsbStreaming][operation:start] completed', JSON.stringify(next));
      refreshUsbDiagnostics('start');
    } catch (error) {
      const message = describeError(error);
      setLastError(message);
      console.error('[SonyUsbStreaming][operation:start] failed', message);
      refreshUsbDiagnostics('start:failed');
    } finally {
      setOperation(null);
    }
  }

  async function stopUsbStreaming() {
    if (!SonyUsbStreaming) return;
    setOperation('stop-usb-streaming');
    try {
      const next = await SonyUsbStreaming.stopStreaming();
      setUsbState(next);
      setUsbPreviewActive(false);
      console.log('[SonyUsbStreaming][operation:stop] completed', JSON.stringify(next));
      refreshUsbDiagnostics('stop');
    } catch (error) {
      const message = describeError(error);
      setLastError(message);
      console.error('[SonyUsbStreaming][operation:stop] failed', message);
    } finally {
      setOperation(null);
    }
  }

  async function captureUsbFrame() {
    if (!SonyUsbStreaming) return;
    setOperation('capture-usb-frame');
    try {
      const photo = await SonyUsbStreaming.capturePreviewFrame();
      setUsbPhotoUri(photo.uri);
      console.log('[SonyUsbStreaming][operation:capture-frame] completed', JSON.stringify(photo));
      refreshUsbDiagnostics('capture-frame');
    } catch (error) {
      const message = describeError(error);
      setLastError(message);
      console.error('[SonyUsbStreaming][operation:capture-frame] failed', message);
      refreshUsbDiagnostics('capture-frame:failed');
    } finally {
      setOperation(null);
    }
  }

  async function runIosCertification() {
    const results: CertificationResult[] = [];
    const publish = () => setCertificationResults([...results]);

    async function step(name: string, task: () => Promise<string> | string) {
      const startedAt = Date.now();
      const result: CertificationResult = {
        name,
        status: 'running',
        detail: 'Running',
        elapsedMs: 0,
      };
      results.push(result);
      publish();
      console.log(`[SonyCamera][certification:${name}] START`);
      try {
        result.detail = await task();
        result.status = 'passed';
        result.elapsedMs = Date.now() - startedAt;
        console.log(
          `[SonyCamera][certification:${name}] PASS ${result.elapsedMs}ms ${result.detail}`
        );
        publish();
      } catch (error) {
        result.status = 'failed';
        result.detail = describeError(error);
        result.elapsedMs = Date.now() - startedAt;
        console.error(
          `[SonyCamera][certification:${name}] FAIL ${result.elapsedMs}ms ${result.detail}`
        );
        publish();
        throw error;
      }
    }

    function requireState(
      next: SonyCameraState,
      accepted: SonyCameraState['state'][],
      label: string
    ) {
      if (!accepted.includes(next.state)) {
        throw new Error(
          `${label}: expected ${accepted.join('/')} but received ${next.state}: ${next.message ?? ''}`
        );
      }
      return `${next.state}: ${next.message ?? 'no message'}`;
    }

    setCertificationRunning(true);
    setOperation('ios-certification');
    setLastError(null);
    setCertificationResults([]);
    eventCounts.current = { state: 0, attached: 0, photo: 0 };
    camera.clearDiagnostics();

    try {
      await step('module-state-api', () => {
        const current = camera.getState();
        return `getState returned ${current.state}`;
      });

      await step('usb-ptp2-connect', async () => {
        const next = await camera.connect({
          preferredProtocol: 'sony_camera_control_ptp2',
          preferredTransport: 'usb',
        });
        setState(next);
        setConnected(isConnectedState(next.state));
        return requireState(next, ['ready', 'streaming'], 'connect');
      });

      await step('connected-diagnostics', () => {
        const snapshot = camera.getDiagnostics();
        if (snapshot.protocol !== 'sony_camera_control_ptp2' || snapshot.transport !== 'usb') {
          throw new Error(`unexpected diagnostics transport ${JSON.stringify(snapshot)}`);
        }
        if (snapshot.entries.length === 0) throw new Error('diagnostic trace is empty');
        setDiagnostics(snapshot.entries);
        return `${snapshot.entries.length} entries, ${snapshot.protocol}/${snapshot.transport}`;
      });

      await step('start-live-view', async () => {
        const next = await camera.startLiveView();
        setState(next);
        setPreviewActive(next.state === 'streaming');
        return requireState(next, ['streaming'], 'startLiveView');
      });

      await step('receive-and-persist-preview-frame', async () => {
        await wait(3_000);
        const photo = await camera.capturePreviewFrame();
        if (!photo.uri || photo.width <= 0 || photo.height <= 0) {
          throw new Error(`invalid preview frame ${JSON.stringify(photo)}`);
        }
        return `${photo.width}x${photo.height} ${photo.mimeType}`;
      });

      await step('stop-live-view', async () => {
        const next = await camera.stopLiveView();
        setState(next);
        setPreviewActive(false);
        return requireState(next, ['ready'], 'stopLiveView');
      });

      await step('full-resolution-photo-transfer', async () => {
        const photo = await camera.capturePhoto();
        setPhotoUri(photo.uri);
        if (!photo.uri || photo.width <= 0 || photo.height <= 0) {
          throw new Error(`invalid captured photo ${JSON.stringify(photo)}`);
        }
        await wait(300);
        return `${photo.width}x${photo.height} ${photo.mimeType}`;
      });

      await step('state-and-photo-events', () => {
        const counts = eventCounts.current;
        if (counts.state === 0) throw new Error('onStateChanged did not fire');
        if (counts.photo === 0) throw new Error('onPhotoCaptured did not fire');
        return `state=${counts.state}, attached=${counts.attached}, photo=${counts.photo}`;
      });

      for (let cycle = 1; cycle <= 3; cycle += 1) {
        await step(`disconnect-reconnect-${cycle}`, async () => {
          const disconnectedState = await camera.disconnect();
          requireState(disconnectedState, ['disconnected'], 'disconnect');
          const connectedState = await camera.connect({
            preferredProtocol: 'sony_camera_control_ptp2',
            preferredTransport: 'usb',
          });
          setState(connectedState);
          setConnected(isConnectedState(connectedState.state));
          return requireState(connectedState, ['ready'], 'reconnect');
        });
      }

      await step('live-view-after-reconnect', async () => {
        const streamingState = await camera.startLiveView();
        requireState(streamingState, ['streaming'], 'restart live view');
        setPreviewActive(true);
        await wait(2_000);
        const frame = await camera.capturePreviewFrame();
        if (frame.width <= 0 || frame.height <= 0) throw new Error('no frame after reconnect');
        const readyState = await camera.stopLiveView();
        setPreviewActive(false);
        setState(readyState);
        requireState(readyState, ['ready'], 'stop restarted live view');
        return `${frame.width}x${frame.height} frame after reconnect`;
      });

      await step('reject-unsupported-ptp3', async () => {
        const rejected = await camera.connect({
          preferredProtocol: 'sony_camera_control_ptp3',
          preferredTransport: 'ptp_ip',
        });
        if (rejected.state !== 'error' || !rejected.message?.includes('not implemented')) {
          throw new Error(`PTP3 did not fail explicitly: ${JSON.stringify(rejected)}`);
        }
        return rejected.message;
      });

      await step('reject-unsupported-scalar', async () => {
        const rejected = await camera.connect({
          preferredProtocol: 'sony_scalar_webapi_v1',
          preferredTransport: 'scalar_http',
        });
        if (rejected.state !== 'error' || !rejected.message?.includes('not implemented')) {
          throw new Error(`Scalar did not fail explicitly: ${JSON.stringify(rejected)}`);
        }
        return rejected.message;
      });

      await step('restore-ready-state', async () => {
        await camera.disconnect();
        const next = await camera.connect({
          preferredProtocol: 'sony_camera_control_ptp2',
          preferredTransport: 'usb',
        });
        setState(next);
        setConnected(isConnectedState(next.state));
        return requireState(next, ['ready'], 'restore connection');
      });

      await step('clear-diagnostics-api', () => {
        camera.clearDiagnostics();
        const snapshot = camera.getDiagnostics();
        if (snapshot.entries.length !== 0) {
          throw new Error(`clearDiagnostics retained ${snapshot.entries.length} entries`);
        }
        setDiagnostics([]);
        return 'diagnostic buffer cleared';
      });

      console.log(`[SonyCamera][certification] COMPLETE passed=${results.length}`);
    } catch (error) {
      const message = describeError(error);
      setLastError(`Certification stopped: ${message}`);
      console.error('[SonyCamera][certification] STOPPED', message);
    } finally {
      setCertificationRunning(false);
      setOperation(null);
      refreshDiagnostics('certification-final');
    }
  }

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>expo-sony-camera</Text>
        <Text style={styles.subtitle}>
          Example app for Sony live view and remote still capture.
        </Text>
        <Text style={styles.sectionTitle}>USB Streaming (UVC)</Text>
        <Text style={styles.sectionHelp}>
          Use the a6700 USB Streaming mode for low-latency video. Remote shutter and Sony property
          control require PC Remote mode instead.
        </Text>
        <View style={styles.preview}>
          <SonyUsbStreamingView active={usbPreviewActive} style={StyleSheet.absoluteFill} />
        </View>
        <Text style={styles.status}>USB state: {usbState?.state ?? 'unavailable'}</Text>
        {usbState?.message ? (
          <Text selectable style={styles.message}>
            USB message: {usbState.message}
          </Text>
        ) : null}
        <Button
          title={usbPreviewActive ? 'Stop USB stream' : 'Start USB stream'}
          disabled={!SonyUsbStreaming || operation !== null}
          onPress={usbPreviewActive ? stopUsbStreaming : startUsbStreaming}
        />
        <Button
          title="Capture USB preview frame"
          disabled={!usbPreviewActive || operation !== null}
          onPress={captureUsbFrame}
        />
        <Button
          title="Refresh USB diagnostics"
          disabled={!SonyUsbStreaming}
          onPress={() => refreshUsbDiagnostics('manual')}
        />
        {usbPhotoUri ? (
          <Text selectable style={styles.photo}>
            USB frame: {usbPhotoUri}
          </Text>
        ) : null}
        <Text style={styles.diagnosticsTitle}>USB diagnostics ({usbDiagnostics.length})</Text>
        <Text selectable style={styles.diagnostics}>
          {usbDiagnostics.length > 0
            ? usbDiagnostics.slice(-40).join('\n')
            : 'No USB streaming diagnostic entries yet.'}
        </Text>

        <Text style={styles.sectionTitle}>PC Remote (PTP2)</Text>
        <Text style={styles.sectionHelp}>
          For iPhone certification, set the a6700 USB Connection Mode to PC Remote and leave the
          camera on the normal shooting screen.
        </Text>
        <View style={styles.preview}>
          <SonyCameraView active={previewActive} style={StyleSheet.absoluteFill} />
        </View>
        <Text style={styles.status}>State: {state?.state ?? camera.getState().state}</Text>
        {state?.message ? (
          <Text selectable style={styles.message}>
            Message: {state.message}
          </Text>
        ) : null}
        {operation ? <Text style={styles.operation}>Running: {operation}</Text> : null}
        {lastError ? (
          <Text selectable style={styles.error}>
            Error: {lastError}
          </Text>
        ) : null}
        <Button
          title={connected ? 'Disconnect' : 'Connect'}
          disabled={operation !== null}
          onPress={connected ? disconnect : connect}
        />
        <Button
          title={previewActive ? 'Stop live view' : 'Start live view'}
          disabled={!connected || operation !== null}
          onPress={toggleLiveView}
        />
        <Button
          title="Capture photo"
          disabled={!connected || operation !== null}
          onPress={capture}
        />
        <Button
          title={
            certificationRunning ? 'iOS certification running...' : 'Run full iOS certification'
          }
          disabled={certificationRunning || operation !== null}
          onPress={runIosCertification}
        />
        {certificationResults.length > 0 ? (
          <View style={styles.certification}>
            <Text style={styles.diagnosticsTitle}>
              Certification:{' '}
              {certificationResults.filter((result) => result.status === 'passed').length}/
              {certificationResults.length} passed
            </Text>
            {certificationResults.map((result) => (
              <Text
                key={result.name}
                selectable
                style={
                  result.status === 'failed' ? styles.certificationFailed : styles.certificationRow
                }>
                {result.status.toUpperCase()} {result.name} ({result.elapsedMs}ms): {result.detail}
              </Text>
            ))}
          </View>
        ) : null}
        <Button title="Refresh diagnostics" onPress={() => refreshDiagnostics('manual')} />
        {photoUri ? (
          <Text selectable style={styles.photo}>
            Captured: {photoUri}
          </Text>
        ) : null}
        <Text style={styles.diagnosticsTitle}>Diagnostics ({diagnostics.length})</Text>
        <Text selectable style={styles.diagnostics}>
          {diagnostics.length > 0
            ? diagnostics.slice(-40).join('\n')
            : 'No native diagnostic entries yet.'}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f4f7f9' },
  content: { gap: 16, padding: 20 },
  title: { fontSize: 28, fontWeight: '800' },
  subtitle: { color: '#52616b', fontSize: 15 },
  sectionTitle: { fontSize: 21, fontWeight: '800', marginTop: 8 },
  sectionHelp: { color: '#52616b', fontSize: 13, lineHeight: 18 },
  preview: { backgroundColor: '#071019', height: 300, overflow: 'hidden' },
  status: { fontFamily: 'monospace' },
  message: { color: '#243746', fontFamily: 'monospace', fontSize: 12 },
  operation: { color: '#72520d', fontFamily: 'monospace', fontSize: 12 },
  error: { color: '#a12424', fontFamily: 'monospace', fontSize: 12 },
  photo: { color: '#1f6f64', fontSize: 12 },
  diagnosticsTitle: { fontSize: 16, fontWeight: '700', marginTop: 8 },
  certification: { backgroundColor: '#edf4ec', gap: 5, padding: 12 },
  certificationRow: { color: '#1f5c35', fontFamily: 'monospace', fontSize: 10 },
  certificationFailed: { color: '#a12424', fontFamily: 'monospace', fontSize: 10 },
  diagnostics: {
    backgroundColor: '#e6ecef',
    color: '#17252e',
    fontFamily: 'monospace',
    fontSize: 10,
    lineHeight: 14,
    padding: 12,
  },
});
