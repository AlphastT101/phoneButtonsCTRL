import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

// Pixel / Material 3 dark palette.
const _bg = Color(0xFF0F1115);
const _surface = Color(0xFF1B1C1F);
const _surfaceHigh = Color(0xFF26272B);
const _divider = Color(0xFF2E3033);
const _text = Color(0xFFE3E3E3);
const _muted = Color(0xFF9AA0A6);
const _accent = Color(0xFFA8C7FA);
const _onAccent = Color(0xFF00315D);
const _iconOff = Color(0xFFC4C7C5);

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: _bg,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  runApp(const VolumeButtonsApp());
}

class VolumeButtonsApp extends StatelessWidget {
  const VolumeButtonsApp({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF4285F4),
      brightness: Brightness.dark,
    ).copyWith(
      primary: _accent,
      onPrimary: _onAccent,
      surface: _surface,
      onSurface: _text,
      secondaryContainer: _surfaceHigh,
      onSecondaryContainer: _text,
      outline: _divider,
    );

    return MaterialApp(
      title: 'Volume Buttons',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: _bg,
        colorScheme: scheme,
        dialogTheme: const DialogThemeData(
          backgroundColor: _surface,
          surfaceTintColor: Colors.transparent,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(28)),
          ),
          titleTextStyle: TextStyle(
            fontSize: 19,
            fontWeight: FontWeight.w600,
            color: _text,
          ),
          contentTextStyle: TextStyle(
            fontSize: 14,
            height: 1.5,
            color: _muted,
          ),
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            backgroundColor: _accent,
            foregroundColor: _onAccent,
            textStyle: const TextStyle(fontWeight: FontWeight.w600),
            shape: const StadiumBorder(),
            padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 12),
          ),
        ),
        snackBarTheme: const SnackBarThemeData(
          behavior: SnackBarBehavior.floating,
          backgroundColor: _surfaceHigh,
          contentTextStyle: TextStyle(color: _text),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(16)),
          ),
        ),
        switchTheme: SwitchThemeData(
          thumbColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected) ? _onAccent : _iconOff,
          ),
          trackColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected)
                ? _accent
                : const Color(0xFF3C4043),
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
      barrierColor: Colors.black.withValues(alpha: 0.6),
      builder: (context) => AlertDialog(
        title: const Text('Display over other apps'),
        content: const Text(
          'Android needs this so the sound panel can float above other apps. '
          'Volume Buttons will be selected on the next screen — just flip the switch.',
        ),
        actionsPadding: const EdgeInsets.fromLTRB(12, 0, 16, 12),
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
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
          children: [
            _buildHeader(),
            const SizedBox(height: 28),
            _buildToggleCard(),
            const SizedBox(height: 28),
            _buildPermissionNote(),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Container(
          width: 50,
          height: 50,
          decoration: const BoxDecoration(color: _accent, shape: BoxShape.circle),
          child: const Icon(Icons.graphic_eq_rounded, color: _onAccent, size: 27),
        ),
        const SizedBox(width: 14),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Volume Buttons',
                style: TextStyle(
                  fontSize: 25,
                  fontWeight: FontWeight.w600,
                  letterSpacing: -0.5,
                  color: _text,
                ),
              ),
              SizedBox(height: 2),
              Text(
                'Sound controls, always within reach.',
                style: TextStyle(fontSize: 13, color: _muted),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildToggleCard() {
    return Container(
      padding: const EdgeInsets.fromLTRB(18, 18, 14, 16),
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(28),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: _enabled ? _accent : _surfaceHigh,
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  Icons.tune_rounded,
                  size: 23,
                  color: _enabled ? _onAccent : _iconOff,
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Floating control',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: _text,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Row(
                      children: [
                        Container(
                          width: 7,
                          height: 7,
                          decoration: BoxDecoration(
                            color: _enabled ? _accent : _muted,
                            shape: BoxShape.circle,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          _enabled ? 'Active on your screen edge' : 'Turned off',
                          style: TextStyle(
                            fontSize: 12,
                            color: _enabled ? _accent : _muted,
                          ),
                        ),
                      ],
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
          const SizedBox(height: 16),
          const Divider(height: 1, thickness: 1, color: _divider),
          const SizedBox(height: 14),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Padding(
                padding: EdgeInsets.only(top: 1),
                child: Icon(Icons.touch_app_rounded, size: 17, color: _muted),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  _enabled
                      ? 'Tap the blue tab on your edge to open sound controls. Drag it to move sides.'
                      : 'Enable it and a blue tab appears on your screen edge, above every app.',
                  style: const TextStyle(fontSize: 12.5, height: 1.45, color: _muted),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildPermissionNote() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(22),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.info_outline_rounded, size: 19, color: _accent),
          const SizedBox(width: 11),
          const Expanded(
            child: Text(
              'Android needs “Display over other apps” for the floating tab. '
              'The app opens the exact settings page for you. Sideloaded installs may also need '
              '“Allow restricted settings” once.',
              style: TextStyle(fontSize: 12, height: 1.5, color: _muted),
            ),
          ),
        ],
      ),
    );
  }
}
