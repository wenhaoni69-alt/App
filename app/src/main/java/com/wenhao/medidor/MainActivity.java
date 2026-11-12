package com.wenhao.medidor;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.SizeF;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Medición sin ARCore: la cámara muestra la imagen y los sensores de orientación
 * del móvil calculan dónde toca el suelo la línea de la cruz central, sabiendo
 * a qué altura se sostiene el móvil.
 */
public class MainActivity extends ComponentActivity implements SensorEventListener {

    private static final String TAG = "Medidor";
    private static final Locale ES = new Locale("es", "ES");
    private static final float MAX_DIST = 15f;
    private static final float NEAR = 0.05f;

    private PreviewView previewView;
    private OverlayView overlay;
    private TextView hintText, summaryText;
    private Button btnMode, btnPhoneH;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private final float[] rot = new float[9];
    private boolean hasRotation = false;

    private SharedPreferences prefs;
    private float phoneHeight = 1.40f;
    private float focalPx = 0f;
    private float vfovLongSideRad = (float) Math.toRadians(74);

    private boolean heightMode = false;

    /** Medida de distancia (cadena de puntos en el suelo) o de altura (base + altura). */
    private static class Measure {
        final boolean height;
        final List<float[]> pts = new ArrayList<>();
        float top = Float.NaN;

        Measure(boolean height) {
            this.height = height;
        }
    }

    private final List<Measure> measures = new ArrayList<>();
    private boolean startNewChain = true;
    private String lastHint, lastSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        overlay = findViewById(R.id.overlay);
        hintText = findViewById(R.id.hint);
        summaryText = findViewById(R.id.summary);
        btnMode = findViewById(R.id.btnMode);
        btnPhoneH = findViewById(R.id.btnPhoneH);

        prefs = getSharedPreferences("medidor", MODE_PRIVATE);
        phoneHeight = prefs.getFloat("phoneHeight", 1.40f);
        updatePhoneHButton();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        findViewById(R.id.btnAdd).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            addPoint();
        });
        findViewById(R.id.btnUndo).setOnClickListener(v -> undo());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            measures.clear();
            startNewChain = true;
            refresh();
        });
        btnMode.setOnClickListener(v -> {
            heightMode = !heightMode;
            btnMode.setText(heightMode ? "Altura" : "Distancia");
            startNewChain = true;
            refresh();
        });
        btnPhoneH.setOnClickListener(v -> askPhoneHeight());

        final View top = findViewById(R.id.topBar);
        final View controls = findViewById(R.id.controls);
        final int base = Math.round(12 * getResources().getDisplayMetrics().density);
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            top.setPadding(top.getPaddingLeft(), base + insets.getSystemWindowInsetTop(), top.getPaddingRight(), top.getPaddingBottom());
            controls.setPadding(controls.getPaddingLeft(), controls.getPaddingTop(), controls.getPaddingRight(), base + insets.getSystemWindowInsetBottom());
            return insets;
        });

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            setHint("Se necesita permiso de cámara para medir");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            setHint("Este móvil no tiene sensor de orientación");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                @SuppressWarnings("deprecation")
                Preview preview = new Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.unbindAll();
                Camera camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
                readFov(camera.getCameraInfo());
            } catch (Exception e) {
                Log.e(TAG, "Error cámara", e);
                setHint("No se pudo abrir la cámara");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void readFov(CameraInfo info) {
        try {
            Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
            SizeF size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focals = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (size != null && focals != null && focals.length > 0) {
                float longSide = Math.max(size.getWidth(), size.getHeight());
                vfovLongSideRad = (float) (2 * Math.atan(longSide / (2 * focals[0])));
            }
        } catch (Exception e) {
            Log.w(TAG, "Sin datos de FOV", e);
        }
        focalPx = 0f;
    }

    // ---------------- Sensores ----------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rot, event.values);
        hasRotation = true;
        refresh();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** Dirección de la cámara (hacia donde apunta la parte trasera) en coordenadas del mundo, Z arriba. */
    private float[] forward() {
        return new float[]{-rot[2], -rot[5], -rot[8]};
    }

    /** Punto del suelo bajo la cruz, o null si no apunta al suelo o está demasiado lejos. */
    private float[] floorHit() {
        if (!hasRotation) return null;
        float[] d = forward();
        if (d[2] > -0.05f) return null;
        float t = phoneHeight / -d[2];
        float x = t * d[0], y = t * d[1];
        if (Math.sqrt(x * x + y * y) > MAX_DIST) return null;
        return new float[]{x, y, 0f};
    }

    /** Altura del punto al que apunta la cruz, sobre la vertical de la base. */
    private float heightAbove(float[] baseP) {
        float[] d = forward();
        float bd = (float) Math.sqrt(baseP[0] * baseP[0] + baseP[1] * baseP[1]);
        if (bd < 0.05f) return Float.NaN;
        float ux = baseP[0] / bd, uy = baseP[1] / bd;
        float along = d[0] * ux + d[1] * uy;
        if (along < 0.05f) return Float.NaN;
        float s = bd / along;
        return phoneHeight + s * d[2];
    }

    // ---------------- Acciones ----------------

    private Measure last() {
        return measures.isEmpty() ? null : measures.get(measures.size() - 1);
    }

    private void addPoint() {
        Measure m = last();
        if (heightMode) {
            if (m != null && m.height && Float.isNaN(m.top) && !startNewChain) {
                float h = heightAbove(m.pts.get(0));
                if (Float.isNaN(h) || h < 0) {
                    toast("Apunta a la parte de arriba del objeto");
                    return;
                }
                m.top = h;
                startNewChain = true;
            } else {
                float[] p = floorHit();
                if (p == null) {
                    toast("Apunta a la base del objeto, donde toca el suelo");
                    return;
                }
                Measure n = new Measure(true);
                n.pts.add(p);
                measures.add(n);
                startNewChain = false;
            }
        } else {
            float[] p = floorHit();
            if (p == null) {
                toast("Apunta la cruz al suelo (máx. " + (int) MAX_DIST + " m)");
                return;
            }
            if (m == null || m.height || startNewChain) {
                m = new Measure(false);
                measures.add(m);
                startNewChain = false;
            }
            m.pts.add(p);
        }
        refresh();
    }

    private void undo() {
        Measure m = last();
        if (m == null) return;
        if (m.height && !Float.isNaN(m.top)) {
            m.top = Float.NaN;
            startNewChain = false;
        } else {
            m.pts.remove(m.pts.size() - 1);
            if (m.pts.isEmpty()) {
                measures.remove(measures.size() - 1);
                startNewChain = true;
            } else {
                startNewChain = false;
            }
        }
        refresh();
    }

    private void askPhoneHeight() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format(ES, "%.0f", phoneHeight * 100));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Altura del móvil sobre el suelo (cm)")
                .setMessage("A la altura a la que sujetas el móvil al medir. Pecho ≈ 130–140 cm.")
                .setView(input)
                .setPositiveButton("Guardar", (dlg, w) -> {
                    try {
                        float cm = Float.parseFloat(input.getText().toString().replace(',', '.'));
                        if (cm < 20 || cm > 300) throw new NumberFormatException();
                        phoneHeight = cm / 100f;
                        prefs.edit().putFloat("phoneHeight", phoneHeight).apply();
                        measures.clear();
                        startNewChain = true;
                        updatePhoneHButton();
                        refresh();
                    } catch (NumberFormatException e) {
                        toast("Valor no válido (20–300 cm)");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updatePhoneHButton() {
        btnPhoneH.setText(String.format(ES, "%.2f m", phoneHeight));
    }

    // ---------------- Pantalla ----------------

    private void refresh() {
        if (!hasRotation) return;
        int w = overlay.getWidth(), h = overlay.getHeight();
        if (w == 0 || h == 0) return;
        if (focalPx == 0f) focalPx = (h / 2f) / (float) Math.tan(vfovLongSideRad / 2);

        OverlayView.Snapshot snap = new OverlayView.Snapshot();
        float[] hit = floorHit();
        Measure cur = last();
        boolean pendingHeight = heightMode && cur != null && cur.height && Float.isNaN(cur.top) && !startNewChain;
        snap.reticleOk = pendingHeight ? !Float.isNaN(heightAbove(cur.pts.get(0))) : hit != null;

        String summary = "Sin medidas";
        for (int mi = 0; mi < measures.size(); mi++) {
            Measure m = measures.get(mi);
            boolean isLast = mi == measures.size() - 1;
            if (m.height) {
                float[] b = m.pts.get(0);
                addPointMarker(snap, b);
                if (!Float.isNaN(m.top)) {
                    float[] t = {b[0], b[1], m.top};
                    addSegment(snap, b, t, fmt(m.top));
                    addPointMarker(snap, t);
                    if (isLast) summary = "Altura: " + fmt(m.top);
                } else if (isLast) {
                    summary = "Base marcada · apunta arriba";
                }
            } else {
                float total = 0f, lastSeg = 0f;
                for (int i = 0; i + 1 < m.pts.size(); i++) {
                    float d = dist(m.pts.get(i), m.pts.get(i + 1));
                    total += d;
                    lastSeg = d;
                    addSegment(snap, m.pts.get(i), m.pts.get(i + 1), fmt(d));
                }
                for (float[] p : m.pts) addPointMarker(snap, p);
                if (isLast) {
                    summary = m.pts.size() == 1 ? "Marca el siguiente punto"
                            : String.format(ES, "Último tramo: %s  ·  Total: %s", fmt(lastSeg), fmt(total));
                }
            }
        }
        if (measures.size() > 1) summary = "Medida " + measures.size() + "  ·  " + summary;

        // Medida en directo
        String hint;
        if (pendingHeight) {
            float[] b = cur.pts.get(0);
            float hh = heightAbove(b);
            if (!Float.isNaN(hh) && hh >= 0) {
                float[] seg = projectSegment(b, new float[]{b[0], b[1], hh}, w, h);
                if (seg != null) snap.liveLine = seg;
                snap.liveText = fmt(hh);
            }
            hint = "Apunta a la parte de arriba y pulsa +";
        } else if (heightMode) {
            hint = hit == null ? "Apunta a la base del objeto, donde toca el suelo"
                    : "Pulsa + para marcar la base";
            if (hit != null) snap.liveText = "a " + fmt(horiz(hit)) + " de ti";
        } else {
            boolean chainOpen = cur != null && !cur.height && !startNewChain && !cur.pts.isEmpty();
            if (hit == null) {
                hint = "Apunta la cruz al suelo";
            } else if (chainOpen) {
                float[] lp = cur.pts.get(cur.pts.size() - 1);
                float[] seg = projectSegment(lp, hit, w, h);
                if (seg != null) snap.liveLine = seg;
                snap.liveText = fmt(dist(lp, hit));
                hint = "";
            } else {
                snap.liveText = "a " + fmt(horiz(hit)) + " de ti";
                hint = "Pulsa + para marcar el primer punto";
            }
        }
        setHint(hint);
        setSummary(summary);
        overlay.setSnapshot(snap);
    }

    private void addPointMarker(OverlayView.Snapshot snap, float[] p) {
        float[] s = projectPoint(p, overlay.getWidth(), overlay.getHeight());
        if (s != null) snap.points.add(s);
    }

    private void addSegment(OverlayView.Snapshot snap, float[] a, float[] b, String label) {
        int w = overlay.getWidth(), h = overlay.getHeight();
        float[] seg = projectSegment(a, b, w, h);
        if (seg != null) snap.lines.add(seg);
        float[] mid = projectPoint(new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}, w, h);
        if (mid != null) snap.labels.add(new OverlayView.Label(mid[0], mid[1], label, false));
    }

    /** Mundo → coordenadas del dispositivo (x derecha, y arriba, z hacia el usuario). */
    private float[] toDevice(float[] p) {
        float vx = p[0], vy = p[1], vz = p[2] - phoneHeight;
        return new float[]{
                rot[0] * vx + rot[3] * vy + rot[6] * vz,
                rot[1] * vx + rot[4] * vy + rot[7] * vz,
                rot[2] * vx + rot[5] * vy + rot[8] * vz};
    }

    private float[] devToScreen(float[] d, int w, int h) {
        float depth = -d[2];
        return new float[]{w / 2f + focalPx * d[0] / depth, h / 2f - focalPx * d[1] / depth};
    }

    private float[] projectPoint(float[] p, int w, int h) {
        float[] d = toDevice(p);
        if (-d[2] < NEAR) return null;
        return devToScreen(d, w, h);
    }

    private float[] projectSegment(float[] a, float[] b, int w, int h) {
        float[] da = toDevice(a), db = toDevice(b);
        boolean aBehind = -da[2] < NEAR, bBehind = -db[2] < NEAR;
        if (aBehind && bBehind) return null;
        if (aBehind) da = clipNear(da, db);
        else if (bBehind) db = clipNear(db, da);
        float[] sa = devToScreen(da, w, h), sb = devToScreen(db, w, h);
        return new float[]{sa[0], sa[1], sb[0], sb[1]};
    }

    private static float[] clipNear(float[] behind, float[] front) {
        float t = (NEAR - (-behind[2])) / ((-front[2]) - (-behind[2]));
        float[] r = new float[3];
        for (int i = 0; i < 3; i++) r[i] = behind[i] + (front[i] - behind[i]) * t;
        return r;
    }

    private static float dist(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float horiz(float[] p) {
        return (float) Math.sqrt(p[0] * p[0] + p[1] * p[1]);
    }

    private static String fmt(float m) {
        if (m < 1f) return String.format(ES, "%.0f cm", m * 100f);
        return String.format(ES, "%.2f m", m);
    }

    private void setHint(String s) {
        if (s.equals(lastHint)) return;
        lastHint = s;
        hintText.setText(s);
        hintText.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void setSummary(String s) {
        if (s.equals(lastSummary)) return;
        lastSummary = s;
        summaryText.setText(s);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
