# Tesseract para el instalador de Windows de 32 bits (ver x64-windows-static-rel.cmake). En 32 bits Tesseract es
# el único lector de tickets (PaddleOCR/ONNX Runtime no existen para x86).
set(VCPKG_TARGET_ARCHITECTURE x86)
set(VCPKG_CRT_LINKAGE static)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_BUILD_TYPE release)
