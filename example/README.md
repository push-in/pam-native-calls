# Calls demo

A one-screen PAM Native app that rings, shows an ongoing call and logs every
call action delivered by `pushinbr/pam-native-calls`.

```bash
cd example
pam composer install
pam doctor --fix
pam dev            # or: pam build
```

The app installs the released package from Packagist. Tap **Ring in 5 s**, lock the phone and watch the full-screen call
UI; Accept, Decline, the timeout and Hang up appear in the log when PHP
resumes. Push-driven ringing needs Firebase (Android) or APNs VoIP (iOS); the
mapping registered in `CallsDemo::boot()` matches a data push such as
`{"type":"call.incoming","call_id":"demo-1","caller_name":"Ana"}`.
