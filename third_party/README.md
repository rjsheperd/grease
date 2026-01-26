# Third Party Libraries

## libffi

1. Download & un-tar the release:
```bash
curl -sLO https://github.com/libffi/libffi/releases/download/v3.5.2/libffi-3.5.2.tar.gz
tar zxf libffi-3.5.2.tar.gz

ln -s $PWD/libffi-3.5.2 $PWD/libffi

```

2. Build for iOS 16 (make sure you have iOS 16 SDK installed via XCode)
```bash
# Set iOS 16 arm64 environment
export CC="xcrun -sdk iphoneos clang -arch arm64 -target arm64-apple-ios16.0"
export CXX="xcrun -sdk iphoneos clang++ -arch arm64 -target arm64-apple-ios16.0"
export CFLAGS="-mios-version-min=16.0 -arch arm64"
export LDFLAGS="-arch arm64"

# Configure + build
./configure --host=aarch64-apple-ios --enable-static --disable-shared

make
````

3. Generate bindings:
```bash
python3 generate-darwin-source-and-headers.py --only-ios
```
