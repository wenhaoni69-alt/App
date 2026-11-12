package com.wenhao.medidor;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity implements GLSurfaceView.Renderer {

    private static final String TAG = "Medidor";
    private static final float NEAR = 0.05f;
    private static final Locale ES = new Locale("es", "ES");

    private GLSurfaceView surfaceView;
    private OverlayView overlay;
    private TextView hintText;
    private TextView summaryText;

    private Session session;
    private boolean installRequested;
    private final BackgroundRenderer background = new BackgroundRenderer();

    private volatile boolean viewportChanged;
    private volatile int vw, vh, rotation;

    /** Una medida: cadena de puntos; si está cerrada es un área. Solo se toca en el hilo GL. */
    private static class Chain {
        final List<Anchor> anchors = new ArrayList<>();
        boolean closed;
    }

    private final List<Chain> chains = new ArrayList<>();
    private final AtomicBoolean addRequested = new AtomicBoolean(false);
    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    private final float[] vp = new float[16];
    private String lastHint = null;
    private String lastSummary = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surface);
        overlay = findViewById(R.id.overlay);
        hintText = findViewById(R.id.hint);
        summaryText = findViewById(R.id.summary);

        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        findViewById(R.id.btnAdd).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            addRequested.set(true);
        });
        findViewById(R.id.btnUndo).setOnClickListener(v -> surfaceView.queueEvent(this::undo));
        findViewById(R.id.btnNew).setOnClickListener(v -> surfaceView.queueEvent(this::newChain));
        findViewById(R.id.btnArea).setOnClickListener(v -> surfaceView.queueEvent(this::toggleArea));
        findViewById(R.id.btnClear).setOnClickListener(v -> surfaceView.queueEvent(this::clearAll));

        final View top = findViewById(R.id.topBar);
        final View controls = findViewById(R.id.controls);
        final int base = dp(12);
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int t = insets.getSystemWindowInsetTop();
            int b = insets.getSystemWindowInsetBottom();
            top.setPadding(top.getPaddingLeft(), base + t, top.getPaddingRight(), top.getPaddingBottom());
            controls.setPadding(controls.getPaddingLeft(), controls.getPaddingTop(), controls.getPaddingRight(), base + b);
            return insets;
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session == null) {
            try {
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                    return;
                }
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }
                session = new Session(this);
                Config config = new Config(session);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                }
                session.configure(config);
            } catch (UnavailableDeviceNotCompatibleException e) {
                showError("Este móvil no es compatible con ARCore.");
                return;
            } catch (UnavailableUserDeclinedInstallationException | UnavailableArcoreNotInstalledException e) {
                showError("Instala \"Servicios de Google Play para RA\" desde Play Store.");
                return;
            } catch (UnavailableApkTooOldException e) {
                showError("Actualiza \"Servicios de Google Play para RA\" en Play Store.");
                return;
            } catch (UnavailableSdkTooOldException e) {
                showError("Actualiza la app Medidor.");
                return;
            } catch (Exception e) {
                Log.e(TAG, "Error creando sesión", e);
                showError("Error al iniciar RA: " + e.getMessage());
                return;
            }
        }
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            showError("Cámara no disponible. Cierra otras apps que la usen y vuelve a abrir.");
            session = null;
            return;
        }
        surfaceView.onResume();
        updateRotation();
        viewportChanged = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            showError("Se necesita permiso de cámara para medir.");
        }
    }

    @SuppressWarnings("deprecation")
    private void updateRotation() {
        if (Build.VERSION.SDK_INT >= 30 && getDisplay() != null) {
            rotation = getDisplay().getRotation();
        } else {
            rotation = getWindowManager().getDefaultDisplay().getRotation();
        }
    }

    private void showError(String msg) {
        runOnUiThread(() -> {
            hintText.setText(msg);
            hintText.setVisibility(View.VISIBLE);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void setHint(String s) {
        if (s.equals(lastHint)) return;
        lastHint = s;
        runOnUiThread(() -> {
            hintText.setText(s);
            hintText.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void setSummary(String s) {
        if (s.equals(lastSummary)) return;
        lastSummary = s;
        runOnUiThread(() -> summaryText.setText(s));
    }

    // ---------------- Acciones (hilo GL) ----------------

    private Chain currentChainForAdd() {
        Chain last = chains.isEmpty() ? null : chains.get(chains.size() - 1);
        if (last == null || last.closed) {
            last = new Chain();
            chains.add(last);
        }
        return last;
    }

    private void undo() {
        if (chains.isEmpty()) return;
        Chain c = chains.get(chains.size() - 1);
        if (c.closed) {
            c.closed = false;
            return;
        }
        if (!c.anchors.isEmpty()) {
            c.anchors.remove(c.anchors.size() - 1).detach();
        }
        if (c.anchors.isEmpty()) {
            chains.remove(chains.size() - 1);
        }
    }

    private void newChain() {
        if (!chains.isEmpty() && !chains.get(chains.size() - 1).anchors.isEmpty()) {
            chains.add(new Chain());
        }
    }

    private void toggleArea() {
        if (chains.isEmpty()) {
            toast("Marca al menos 3 puntos para calcular un área");
            return;
        }
        Chain c = chains.get(chains.size() - 1);
        if (c.anchors.size() < 3) {
            toast("Marca al menos 3 puntos para calcular un área");
            return;
        }
        c.closed = !c.closed;
    }

    private void clearAll() {
        for (Chain c : chains) {
            for (Anchor a : c.anchors) a.detach();
        }
        chains.clear();
    }

    // ---------------- Render ----------------

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        background.createOnGlThread();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        vw = width;
        vh = height;
        updateRotation();
        viewportChanged = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null) return;
        if (viewportChanged) {
            s.setDisplayGeometry(rotation, vw, vh);
            viewportChanged = false;
        }
        try {
            s.setCameraTextureName(background.getTextureId());
            Frame frame = s.update();
            Camera camera = frame.getCamera();
            background.draw(frame);

            if (camera.getTrackingState() != TrackingState.TRACKING) {
                addRequested.set(false);
                overlay.setSnapshot(null);
                setHint(trackingHint(camera.getTrackingFailureReason()));
                return;
            }

            camera.getProjectionMatrix(proj, 0, NEAR, 100f);
            camera.getViewMatrix(view, 0);
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

            HitResult hit = centerHit(frame, camera);

            if (addRequested.getAndSet(false)) {
                if (hit != null) {
                    currentChainForAdd().anchors.add(hit.createAnchor());
                } else {
                    toast("Apunta a una superficie y mueve el móvil despacio");
                }
            }

            buildSnapshot(hit);
        } catch (Throwable t) {
            Log.e(TAG, "Error en frame", t);
        }
    }

    private HitResult centerHit(Frame frame, Camera camera) {
        List<HitResult> hits = frame.hitTest(vw / 2f, vh / 2f);
        for (HitResult hit : hits) {
            Trackable t = hit.getTrackable();
            if (t instanceof Plane) {
                Plane p = (Plane) t;
                if (p.isPoseInPolygon(hit.getHitPose())
                        && distanceToPlane(hit.getHitPose(), camera.getPose()) > 0) {
                    return hit;
                }
            } else if (t instanceof DepthPoint) {
                return hit;
            } else if (t instanceof Point
                    && ((Point) t).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                return hit;
            }
        }
        return null;
    }

    private static float distanceToPlane(Pose planePose, Pose cameraPose) {
        float[] normal = new float[3];
        planePose.getTransformedAxis(1, 1.0f, normal, 0);
        return (cameraPose.tx() - planePose.tx()) * normal[0]
                + (cameraPose.ty() - planePose.ty()) * normal[1]
                + (cameraPose.tz() - planePose.tz()) * normal[2];
    }

    private void buildSnapshot(HitResult hit) {
        OverlayView.Snapshot snap = new OverlayView.Snapshot();
        snap.reticleOk = hit != null;
        float[] hitPos = hit != null ? hit.getHitPose().getTranslation() : null;

        String summary = "Sin medidas";
        int totalPoints = 0;

        for (int ci = 0; ci < chains.size(); ci++) {
            Chain c = chains.get(ci);
            List<float[]> pts = new ArrayList<>();
            for (Anchor a : c.anchors) {
                if (a.getTrackingState() != TrackingState.STOPPED) {
                    pts.add(a.getPose().getTranslation());
                }
            }
            totalPoints += pts.size();

            float total = 0f;
            float lastSeg = 0f;
            int n = pts.size();
            int segCount = c.closed ? n : n - 1;
            for (int i = 0; i < segCount; i++) {
                float[] a = pts.get(i);
                float[] b = pts.get((i + 1) % n);
                float dist = dist(a, b);
                total += dist;
                if (!c.closed || i < n - 1) lastSeg = dist;
                float[] seg = projectSegment(a, b);
                if (seg != null) snap.lines.add(seg);
                float[] mid = projectPoint(new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2});
                if (mid != null) snap.labels.add(new OverlayView.Label(mid[0], mid[1], fmt(dist), false));
            }
            for (float[] p : pts) {
                float[] sp = projectPoint(p);
                if (sp != null) snap.points.add(sp);
            }

            float area = 0f;
            if (c.closed && n >= 3) {
                area = polygonArea(pts);
                float[] cen = new float[3];
                for (float[] p : pts) {
                    cen[0] += p[0] / n;
                    cen[1] += p[1] / n;
                    cen[2] += p[2] / n;
                }
                float[] sc = projectPoint(cen);
                if (sc != null) {
                    snap.labels.add(new OverlayView.Label(sc[0], sc[1], fmtArea(area), true));
                }
            }

            boolean isLast = ci == chains.size() - 1;
            if (isLast && n > 0) {
                if (c.closed) {
                    summary = String.format(ES, "Área: %s  ·  Perímetro: %s", fmtArea(area), fmt(total));
                } else if (n == 1) {
                    summary = "Marca el siguiente punto";
                } else {
                    summary = String.format(ES, "Último tramo: %s  ·  Total: %s", fmt(lastSeg), fmt(total));
                }
                if (!c.closed && hitPos != null) {
                    float[] last = pts.get(n - 1);
                    float[] seg = projectSegment(last, hitPos);
                    if (seg != null) snap.liveLine = seg;
                    snap.liveText = fmt(dist(last, hitPos));
                }
            }
        }
        if (chains.size() > 1) {
            summary = "Medida " + chains.size() + "  ·  " + summary;
        }

        if (hit == null) {
            setHint("Apunta a una superficie y mueve el móvil despacio");
        } else if (totalPoints == 0) {
            setHint("Pulsa + para marcar el primer punto");
        } else {
            setHint("");
        }
        setSummary(summary);
        overlay.setSnapshot(snap);
    }

    private static String trackingHint(TrackingFailureReason r) {
        switch (r) {
            case INSUFFICIENT_LIGHT:
                return "Poca luz: enciende más luz";
            case EXCESSIVE_MOTION:
                return "Mueve el móvil más despacio";
            case INSUFFICIENT_FEATURES:
                return "Apunta a una zona con más textura";
            case CAMERA_UNAVAILABLE:
                return "Cámara no disponible";
            default:
                return "Mueve el móvil despacio para detectar superficies";
        }
    }

    // ---------------- Geometría ----------------

    private static float dist(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float polygonArea(List<float[]> p) {
        double nx = 0, ny = 0, nz = 0;
        int n = p.size();
        for (int i = 0; i < n; i++) {
            float[] a = p.get(i);
            float[] b = p.get((i + 1) % n);
            nx += a[1] * b[2] - a[2] * b[1];
            ny += a[2] * b[0] - a[0] * b[2];
            nz += a[0] * b[1] - a[1] * b[0];
        }
        return (float) (0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz));
    }

    private static String fmt(float meters) {
        if (meters < 1f) return String.format(ES, "%.1f cm", meters * 100f);
        return String.format(ES, "%.2f m", meters);
    }

    private static String fmtArea(float m2) {
        return String.format(ES, "%.2f m²", m2);
    }

    private float[] toClip(float[] p) {
        float[] in = {p[0], p[1], p[2], 1f};
        float[] out = new float[4];
        Matrix.multiplyMV(out, 0, vp, 0, in, 0);
        return out;
    }

    private float[] clipToScreen(float[] c) {
        float x = c[0] / c[3];
        float y = c[1] / c[3];
        return new float[]{(x * 0.5f + 0.5f) * vw, (1f - (y * 0.5f + 0.5f)) * vh};
    }

    private float[] projectPoint(float[] p) {
        float[] c = toClip(p);
        if (c[3] < NEAR) return null;
        return clipToScreen(c);
    }

    private float[] projectSegment(float[] a, float[] b) {
        float[] ca = toClip(a);
        float[] cb = toClip(b);
        if (ca[3] < NEAR && cb[3] < NEAR) return null;
        if (ca[3] < NEAR) ca = clipNear(ca, cb);
        else if (cb[3] < NEAR) cb = clipNear(cb, ca);
        float[] sa = clipToScreen(ca);
        float[] sb = clipToScreen(cb);
        return new float[]{sa[0], sa[1], sb[0], sb[1]};
    }

    private static float[] clipNear(float[] behind, float[] front) {
        float t = (NEAR - behind[3]) / (front[3] - behind[3]);
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) r[i] = behind[i] + (front[i] - behind[i]) * t;
        return r;
    }
}
