#!/usr/bin/env python3
import re
from PIL import Image

def extract_screens_from_file(path):
    import io
    with open(path, 'r', encoding='utf-8', errors='ignore') as f:
        data = f.read()
    pattern = re.compile(r"screen=\(\s*([\-0-9.]+),\s*([\-0-9.]+),\s*([\-0-9.]+),\s*([\-0-9.]+)\)")
    rects = []
    for m in pattern.finditer(data):
        x = float(m.group(1)); y = float(m.group(2)); w = float(m.group(3)); h = float(m.group(4))
        rects.append((x,y,w,h))
    return rects

def analyze(logfile, imagepath):
    rects = extract_screens_from_file(logfile)
    if not rects:
        print('No rects found')
        return 2
    xs = [x for (x,y,w,h) in rects]
    ys = [y for (x,y,w,h) in rects]
    rights = [x+w for (x,y,w,h) in rects]
    bottoms = [y+h for (x,y,w,h) in rects]
    minx = min(xs); miny = min(ys); maxr = max(rights); maxb = max(bottoms)
    img = Image.open(imagepath)
    imgw, imgh = img.size
    inside_count = 0
    partially_inside = 0
    outside_count = 0
    for (x,y,w,h) in rects:
        left = x; top = y; right = x+w; bottom = y+h
        if right < 0 or bottom < 0 or left > imgw or top > imgh:
            outside_count += 1
        elif left >=0 and top >=0 and right <= imgw and bottom <= imgh:
            inside_count += 1
        else:
            partially_inside +=1
    print('rect_count=', len(rects))
    print('bounds minx,miny,maxr,maxb =', minx, miny, maxr, maxb)
    print('image size =', imgw, imgh)
    print('inside=', inside_count, 'partially_inside=', partially_inside, 'outside=', outside_count)
    return 0

if __name__ == '__main__':
    import sys
    if len(sys.argv) != 3:
        print('Usage: analyze_highlight_bounds.py blueprint_logs_utf8.txt app/screenshot_debug.png')
        sys.exit(2)
    sys.exit(analyze(sys.argv[1], sys.argv[2]))
