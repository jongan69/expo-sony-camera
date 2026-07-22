import SonyCamera, { SonyCameraView } from 'expo-sony-camera';
import { useEffect, useState } from 'react';
import { Button, SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { SonyCameraState } from 'expo-sony-camera';

export default function App() {
  const nativeCamera = SonyCamera;
  const [connected, setConnected] = useState(false);
  const [previewActive, setPreviewActive] = useState(false);
  const [photoUri, setPhotoUri] = useState<string | null>(null);
  const [state, setState] = useState<SonyCameraState | null>(null);

  useEffect(() => {
    if (!nativeCamera) return;
    setState(nativeCamera.getState());
    const subscription = nativeCamera.addListener('onStateChanged', setState);
    return () => subscription.remove();
  }, [nativeCamera]);

  if (!nativeCamera) {
    return (
      <SafeAreaView style={styles.container}>
        <Text>Native Sony camera support is unavailable on web.</Text>
      </SafeAreaView>
    );
  }

  const camera = nativeCamera;

  async function connect() {
    const next = await camera.connect();
    setConnected(next.state === 'ready' || next.state === 'streaming');
  }

  async function capture() {
    const photo = await camera.capturePhoto();
    setPhotoUri(photo.uri);
  }

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>expo-sony-camera</Text>
        <Text style={styles.subtitle}>
          Example app for Sony live view and remote still capture.
        </Text>
        <View style={styles.preview}>
          <SonyCameraView active={previewActive} style={StyleSheet.absoluteFill} />
        </View>
        <Text style={styles.status}>State: {state?.state ?? camera.getState().state}</Text>
        <Button
          title={connected ? 'Disconnect' : 'Connect'}
          onPress={async () =>
            connected
              ? (await camera.disconnect(), setConnected(false), setPreviewActive(false))
              : connect()
          }
        />
        <Button
          title={previewActive ? 'Stop live view' : 'Start live view'}
          disabled={!connected}
          onPress={() => setPreviewActive((value) => !value)}
        />
        <Button title="Capture photo" disabled={!connected} onPress={capture} />
        {photoUri ? (
          <Text selectable style={styles.photo}>
            Captured: {photoUri}
          </Text>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f4f7f9' },
  content: { gap: 16, padding: 20 },
  title: { fontSize: 28, fontWeight: '800' },
  subtitle: { color: '#52616b', fontSize: 15 },
  preview: { backgroundColor: '#071019', height: 300, overflow: 'hidden' },
  status: { fontFamily: 'monospace' },
  photo: { color: '#1f6f64', fontSize: 12 },
});
