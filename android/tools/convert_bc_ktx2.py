#!/usr/bin/env python3
# Converts BC-compressed KTX2 textures (BC1/3/4/5/7, desktop GPU formats) to
# uncompressed RGBA8 KTX2, keeping all mip levels, so phone GPUs without
# textureCompressionBC can use them. Other KTX2 files are copied as they are.
#
#   convert_bc_ktx2.py <input dir> <output dir>
#
# Needs: pip install texture2ddecoder
import os, shutil, struct, sys
import texture2ddecoder as t2d

KTX2_ID = bytes([0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A])

# vkFormat -> (decoder, block bytes, srgb)
BC = {
    131: (t2d.decode_bc1, 8, False),  132: (t2d.decode_bc1, 8, True),
    133: (t2d.decode_bc1, 8, False),  134: (t2d.decode_bc1, 8, True),
    137: (t2d.decode_bc3, 16, False), 138: (t2d.decode_bc3, 16, True),
    139: (t2d.decode_bc4, 8, False),
    141: (t2d.decode_bc5, 16, False),
    145: (t2d.decode_bc7, 16, False), 146: (t2d.decode_bc7, 16, True),
}

def dfd_rgba8(srgb):
    samples = b''
    for i, ch in enumerate([0, 1, 2, 15]):
        samples += struct.pack('<HBB', i * 8, 7, ch) + b'\0\0\0\0' + struct.pack('<II', 0, 255)
    basic = struct.pack('<IHH', 0, 2, 24 + len(samples))
    basic += struct.pack('<BBBB', 1, 1, 2 if srgb else 1, 0)
    basic += b'\0\0\0\0' + bytes([4, 0, 0, 0, 0, 0, 0, 0]) + samples
    return struct.pack('<I', 4 + len(basic)) + basic

def convert(src, dst):
    d = open(src, 'rb').read()
    if d[:12] != KTX2_ID:
        return False
    vkf, type_size, w, h, depth, layers, faces, levels, scheme = struct.unpack_from('<9I', d, 12)
    if vkf not in BC or scheme != 0 or depth > 1 or layers > 1 or faces != 1:
        return False
    decode, block_bytes, srgb = BC[vkf]
    levels = max(1, levels)

    images = []
    for i in range(levels):
        off, length, _ = struct.unpack_from('<3Q', d, 80 + 24 * i)
        lw, lh = max(1, w >> i), max(1, h >> i)
        pw, ph = (lw + 3) // 4 * 4, (lh + 3) // 4 * 4
        need = (pw // 4) * (ph // 4) * block_bytes
        bgra = decode(d[off:off + need], pw, ph)
        rgba = bytearray(lw * lh * 4)
        for y in range(lh):
            row = bgra[y * pw * 4:(y * pw + lw) * 4]
            r = bytearray(row)
            r[0::4], r[2::4] = row[2::4], row[0::4]
            if vkf == 141:          # BC5: two channels, keep alpha opaque
                r[3::4] = b'\xff' * lw
            rgba[y * lw * 4:(y + 1) * lw * 4] = r
        images.append(bytes(rgba))

    dfd = dfd_rgba8(srgb)
    header = 12 + 9 * 4 + 4 * 4 + 2 * 8
    dfd_off = header + 24 * levels
    pos = (dfd_off + len(dfd) + 15) // 16 * 16
    # KTX2 stores the level data smallest first; the index stays largest first
    offsets = [0] * levels
    for i in reversed(range(levels)):
        offsets[i] = pos
        pos += (len(images[i]) + 15) // 16 * 16
    index = [struct.pack('<3Q', offsets[i], len(images[i]), len(images[i])) for i in range(levels)]

    out = bytearray(KTX2_ID)
    out += struct.pack('<9I', 43 if srgb else 37, 1, w, h, 0, 0, 1, levels, 0)
    out += struct.pack('<4I', dfd_off, len(dfd), 0, 0)
    out += struct.pack('<2Q', 0, 0)
    out += b''.join(index) + dfd
    for i in reversed(range(levels)):
        out += b'\0' * (offsets[i] - len(out)) + images[i]
    open(dst, 'wb').write(out)
    return True

def main(src_dir, dst_dir):
    n_conv = n_copy = 0
    for root, _, files in os.walk(src_dir):
        for f in files:
            s = os.path.join(root, f)
            o = os.path.join(dst_dir, os.path.relpath(s, src_dir))
            os.makedirs(os.path.dirname(o), exist_ok=True)
            if f.lower().endswith('.ktx2') and convert(s, o):
                n_conv += 1
            else:
                shutil.copyfile(s, o)
                n_copy += 1
    print('converted %d, copied %d' % (n_conv, n_copy))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
