# Tesseract para el instalador de Windows de 64 bits: todo estático (un solo tesseract.exe, sin DLLs ni el
# runtime de Visual C++, que una PC vieja puede no tener) y solo Release (la mitad del tiempo de compilación).
set(VCPKG_TARGET_ARCHITECTURE x64)
set(VCPKG_CRT_LINKAGE static)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_BUILD_TYPE release)
