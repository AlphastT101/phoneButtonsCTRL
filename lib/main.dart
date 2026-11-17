import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// GitHub dark, pure black canvas.
const _bg = Color(0xFF000000);
const _surface = Color(0xFF161B22);
const _border = Color(0xFF30363D);
const _text = Color(0xFFE6EDF3);
const _muted = Color(0xFF8B949E);
const _subtle = Color(0xFF3D444D);
const _accent = Color(0xFFF0883E);

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Color(0xFF000000),
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  runApp(const VolumeButtonsApp());
}

class VolumeButtonsApp extends StatelessWidget {
  const VolumeButtonsApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Volume Buttons',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: _bg,
        colorScheme: ColorScheme.fromSeed(
          seedColor: _accent,
          brightness: Brightness.dark,
        ).copyWith(surface: _surface, primary: _accent),
        dialogTheme: const DialogThemeData(
          backgroundColor: _surface,
          surfaceTintColor: Colors.transparent,
          shape: RoundedRectangleBorder(
            side: BorderSide(color: _border),
            borderRadius: BorderRadius.all(Radius.circular(12)),
          ),
        ),
        snackBarTheme: const SnackBarThemeData(
          behavior: SnackBarBehavior.floating,
          backgroundColor: Color(0xFF21262D),
          contentTextStyle: TextStyle(color: _text),
        ),
        switchTheme: SwitchThemeData(
          thumbColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected) ? _accent : _subtle,
          ),
          trackColor: WidgetStateProperty.resolveWith(
            (states) =>
                states.contains(WidgetState.selected) ? const Color(0x40F0883E) : const Color(0xFF21262D),
          ),
          trackOutlineColor: WidgetStateProperty.all(Colors.transparent),
        ),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('volume_buttons/overlay');

  bool _enabled = false;
  bool _busy = false;
  bool _pendingEnable = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _onResumed();
    }
  }

  Future<void> _refresh() async {
    try {
      final enabled = await _channel.invokeMethod<bool>('isOverlayEnabled') ?? false;
      if (enabled) {
        // Self-heal after an APK replace: re-assert the service so the tab
        // actually reappears (it only restarts on boot otherwise).
        await _channel.invokeMethod('startOverlay');
      }
      if (!mounted) return;
      setState(() => _enabled = enabled);
    } on PlatformException {
      // Engine not ready yet.
    }
  }

  Future<void> _onResumed() async {
    if (_pendingEnable) {
      final granted = await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;
      if (granted && mounted) {
        _pendingEnable = false;
        await _enable();
      }
      return;
    }
    await _refresh();
  }

  Future<void> _onToggle(bool wantOn) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      if (wantOn) {
        final granted = await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;
        if (!granted) {
          if (mounted) setState(() => _pendingEnable = true);
          _showOverlayDialog();
          return;
        }
        await _enable();
      } else {
        await _channel.invokeMethod('stopOverlay');
        if (mounted) setState(() => _enabled = false);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _enable() async {
    await _channel.invokeMethod('requestNotificationPermission');
    await _channel.invokeMethod('askBatteryOptimizationIfNeeded');
    final ok = await _channel.invokeMethod<bool>('startOverlay') ?? false;
    if (mounted) setState(() => _enabled = ok);
    if (!ok && mounted) _showSnack('Overlay permission is still missing.');
  }

  void _showOverlayDialog() {
    showDialog<void>(
      context: context,
      barrierColor: Colors.black54,
      builder: (context) => AlertDialog(
        title: const Text(
          'Display over other apps',
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: _text),
        ),
        content: const Text(
          'Android needs this so the button can float above other apps. Volume Buttons will be '
          'selected on the next screen — just flip the switch.',
          style: TextStyle(fontSize: 13, height: 1.5, color: _muted),
        ),
        actions: [
          TextButton(
            onPressed: () {
              _pendingEnable = false;
              Navigator.of(context).pop();
            },
            style: TextButton.styleFrom(foregroundColor: _muted),
            child: const Text('Not now'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.of(context).pop();
              _channel.invokeMethod('openOverlaySettings');
            },
            style: FilledButton.styleFrom(
              backgroundColor: _accent,
              foregroundColor: Colors.black,
              textStyle: const TextStyle(fontWeight: FontWeight.w700),
            ),
            child: const Text('Open settings'),
          ),
        ],
      ),
    );
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bg,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 20, 18, 18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Volume Buttons',
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.2,
                  color: _text,
                ),
              ),
              const SizedBox(height: 6),
              const Text(
                'Volume keys dead? This replaces them.',
                style: TextStyle(fontSize: 13, height: 1.4, color: _muted),
              ),
              const SizedBox(height: 24),
              _buildToggleCard(),
              const SizedBox(height: 14),
              const Text(
                'The orange tab sits on the left or right edge of your screen. Drag it to move it, tap it to open the volume slider.',
                style: TextStyle(fontSize: 12, height: 1.5, color: _muted),
              ),
              const Spacer(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildToggleCard() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(12),
        border: const Border.fromBorderSide(BorderSide(color: _border)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Floating volume button',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: _text),
                ),
                const SizedBox(height: 3),
                Text(
                  _enabled ? 'On — the tab is on your screen edge' : 'Off',
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.3,
                    color: _enabled ? _accent : _muted,
                  ),
                ),
              ],
            ),
          ),
          Switch(
            value: _enabled,
            onChanged: _busy ? null : _onToggle,
          ),
        ],
      ),
    );
  }
}
