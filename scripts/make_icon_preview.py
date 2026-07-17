from pathlib import Path
try:
    from PIL import Image, ImageDraw
except ImportError:
    import subprocess, sys
    subprocess.check_call([sys.executable, "-m", "pip", "install", "pillow", "-q"])
    from PIL import Image, ImageDraw

size = 512
img = Image.new("RGBA", (size, size), (21, 101, 192, 255))
draw = ImageDraw.Draw(img)
pad = 70
draw.ellipse([pad, pad, size-pad, size-pad], fill=(66, 165, 245, 90))
cx, cy = 256, 280
draw.ellipse([cx-140, cy-40, cx+40, cy+90], fill=(255,255,255,255))
draw.ellipse([cx-40, cy-70, cx+130, cy+70], fill=(255,255,255,255))
draw.ellipse([cx-90, cy-110, cx+50, cy+20], fill=(255,255,255,255))
draw.rounded_rectangle([cx-120, cy+10, cx+120, cy+95], radius=30, fill=(255,255,255,255))
arrow = (13, 71, 161, 255)
draw.rectangle([236, 250, 276, 360], fill=arrow)
draw.polygon([(256, 190), (200, 265), (312, 265)], fill=arrow)
out = Path(r"D:\MyOpenSource\webdav-uploader\docs\app-icon-preview.png")
out.parent.mkdir(parents=True, exist_ok=True)
img.save(out)
print(out, out.stat().st_size)
