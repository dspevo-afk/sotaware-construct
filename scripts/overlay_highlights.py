#!/usr/bin/env python3
import sys
import re
from PIL import Image, ImageDraw

def extract_screens(logtext):
    pattern = re.compile(r"screen=\(\s*([\-0-9.]+),\s*([\-0-9.]+),\s*([\-0-9.]+),\s*([\-0-9.]+)\)")
    rects = []
    for m in pattern.finditer(logtext):
        x = float(m.group(1))
        y = float(m.group(2))
        w = float(m.group(3))
        h = float(m.group(4))
        rects.append((x, y, w, h))
    return rects

def draw_overlay(image_path, log_path, out_path):
    with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
        logtext = f.read()
    rects = extract_screens(logtext)
    if not rects:
        print('No screen=() rects found in', log_path)
        return 1
    img = Image.open(image_path).convert('RGBA')
    overlay = Image.new('RGBA', img.size, (0,0,0,0))
    draw = ImageDraw.Draw(overlay)
    drawn = set()
    for (x,y,w,h) in rects:
        key = (round(x), round(y), round(w), round(h))
        if key in drawn:
            continue
        drawn.add(key)
        left = x
        top = y
        right = x + w
        bottom = y + h
        draw.rectangle([left, top, right, bottom], outline=(255,0,0,255), width=6)
        draw.rectangle([left, top, right, bottom], fill=(255,0,0,64))
    combined = Image.alpha_composite(img, overlay)
    combined.save(out_path)
    print('Wrote', out_path, 'with', len(drawn), 'rects')
    return 0

if __name__ == '__main__':
    if len(sys.argv) != 4:
        print('Usage: overlay_highlights.py input_screenshot.png input_logs.txt output_overlay.png')
        sys.exit(2)
    sys.exit(draw_overlay(sys.argv[1], sys.argv[2], sys.argv[3]))
