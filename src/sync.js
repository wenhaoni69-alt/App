// Capa de datos: Firebase Auth + Firestore. Se compila con esbuild a dist/sync.js.
import { initializeApp } from 'firebase/app';
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager,
  collection, doc, setDoc, deleteDoc, onSnapshot, query, where, getDocs
} from 'firebase/firestore';

const DAY = 86400000;
const LIVE_DAYS = 60; // en tiempo real solo los últimos 60 días; lo anterior se carga bajo demanda

function startOfDay(ts) { const d = new Date(ts); d.setHours(0, 0, 0, 0); return d.getTime(); }

function toEvent(d) {
  const x = d.data();
  return { id: d.id, type: x.type, ts: x.ts, ml: x.ml == null ? null : x.ml, note: x.note || '' };
}

function makeSession(db, user, logout) {
  const App = window.App;
  const col = collection(db, 'eventos');
  const ajustes = doc(db, 'ajustes', 'general');
  const liveFrom = startOfDay(Date.now()) - LIVE_DAYS * DAY;
  let loadedFrom = liveFrom;
  let live = new Map();
  const old = new Map();
  let evCb = null, setCb = null, stCb = null;
  let lastEvents = null, lastSettings = null, lastStatus = { pending: false };
  const onErr = (e) => App.syncError(e);

  function emit() {
    const all = new Map(old);
    live.forEach((v, k) => all.set(k, v));
    lastEvents = Array.from(all.values());
    if (evCb) evCb(lastEvents);
  }

  const unsubs = [
    onSnapshot(query(col, where('ts', '>=', liveFrom)), { includeMetadataChanges: true }, (snap) => {
      const m = new Map();
      snap.forEach((d) => m.set(d.id, toEvent(d)));
      live = m;
      emit();
      lastStatus = { pending: snap.metadata.hasPendingWrites };
      if (stCb) stCb(lastStatus);
    }, onErr),
    onSnapshot(ajustes, { includeMetadataChanges: true }, (snap) => {
      const exists = snap.exists();
      lastSettings = [
        { defaultMl: exists ? (snap.data().defaultMl || null) : null },
        { known: exists || !snap.metadata.fromCache }
      ];
      if (setCb) setCb(lastSettings[0], lastSettings[1]);
    }, onErr)
  ];

  const store = {
    email: user.email,
    loadedFrom: () => loadedFrom,
    onEvents(cb) { evCb = cb; if (lastEvents) cb(lastEvents); },
    onSettings(cb) { setCb = cb; if (lastSettings) cb(lastSettings[0], lastSettings[1]); },
    onStatus(cb) { stCb = cb; cb(lastStatus); },
    put(ev) {
      const data = { type: ev.type, ts: ev.ts, ml: ev.type === 'feed' ? ev.ml : null, note: ev.note || '' };
      if (ev.ts < liveFrom) { old.set(ev.id, { id: ev.id, ...data }); emit(); }
      else if (old.delete(ev.id)) emit();
      // Sin await: si no hay conexión Firestore lo guarda y lo sube al volver
      setDoc(doc(col, ev.id), data).catch(onErr);
    },
    remove(id) {
      if (old.delete(id)) emit();
      deleteDoc(doc(col, id)).catch(onErr);
    },
    setDefaultMl(v) { setDoc(ajustes, { defaultMl: v }, { merge: true }).catch(onErr); },
    ensureFrom(ts) {
      if (ts >= loadedFrom) return Promise.resolve();
      const q = query(col, where('ts', '>=', ts), where('ts', '<', loadedFrom));
      return getDocs(q).then((snap) => {
        snap.forEach((d) => { if (!live.has(d.id)) old.set(d.id, toEvent(d)); });
        loadedFrom = ts;
        emit();
      });
    },
    logout
  };
  return { store, stop() { unsubs.forEach((u) => u()); } };
}

function boot() {
  const App = window.App;
  const cfg = window.FIREBASE_CONFIG;
  if (!cfg || !cfg.apiKey) { App.setState('setup'); return; }

  const app = initializeApp(cfg);
  const auth = getAuth(app);
  const db = initializeFirestore(app, {
    localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() })
  });

  let session = null;
  onAuthStateChanged(auth, (user) => {
    if (session) { session.stop(); session = null; }
    if (!user) {
      App.setState('login', { signIn: (email, pass) => signInWithEmailAndPassword(auth, email, pass) });
      return;
    }
    session = makeSession(db, user, () => signOut(auth));
    App.setState('ready', session.store);
  });
}

boot();
