"""从生成的图标原图派生出 Android 启动图标资源。

输入：vibe_images/sogousym_icon_v2_*.png (1024x1024, 右下角带水印)
输出：
  app/src/main/res/drawable-nodpi/ic_launcher_art.png     自适应图标背景(整图, 水印已修补)
  app/src/main/res/mipmap-*/ic_launcher.png               旧版方形图标 (中心裁剪+圆角)
  app/src/main/res/mipmap-*/ic_launcher_round.png          旧版圆形图标
  vibe_images/preview_*.png                               自检预览
"""
import glob
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
VIBE = os.path.join(ROOT, "vibe_images")

# 源图：命令行传入，缺省取 vibe_images 下最新的 icon_*.png（排除派生产物）
if len(sys.argv) > 1:
    src = sys.argv[1]
else:
    cand = [p for p in glob.glob(os.path.join(VIBE, "icon_*.png"))
            if os.path.basename(p) not in ("icon_master.png",)]
    src = max(cand, key=os.path.getmtime)
art = Image.open(src).convert("RGB")
W, H = art.size
print("source:", src, art.size)

# ---------------------------------------------------------------- 1. 修补水印
# 水印位于右下角；用其正上方两行的颜色按列做线性外推填掉（该区域是平滑渐变）。
X0, X1 = 760, W          # 覆盖水印横向范围（含余量）
Y0, Y1 = 905, H          # 覆盖水印纵向范围
px = art.load()


def clampi(v):
    return 0 if v < 0 else (255 if v > 255 else int(v))


SPAN = 24  # 取窗平均，避免单行噪点被放大成条纹


def row_avg(x, y_from, y_to):
    n = y_to - y_from
    return [sum(px[x, y][i] for y in range(y_from, y_to)) / n for i in range(3)]


for x in range(X0, X1):
    a = row_avg(x, Y0 - 2 * SPAN, Y0 - SPAN)
    b = row_avg(x, Y0 - SPAN, Y0)
    slope = [(b[i] - a[i]) / (SPAN - 1) for i in range(3)]
    for y in range(Y0, Y1):
        step = y - (Y0 - 1)
        px[x, y] = tuple(clampi(b[i] + slope[i] * step) for i in range(3))
# 主体在整图里偏小，中心裁剪放大到合适比例（纯裁剪、无接缝）
MASTER_CROP = 0.85
ms = int(W * MASTER_CROP)
mo = (W - ms) // 2
work = art.crop((mo, mo, mo + ms, mo + ms))
work.save(os.path.join(VIBE, "icon_master.png"))
print("watermark patched + center-cropped %.2f -> %s"
      % (MASTER_CROP, os.path.join(VIBE, "icon_master.png")))

# ------------------------------------------------------------- 2. 自适应背景图
# 铺满 108dp 层，可见区=中心 72/108，故主体需落在中心约 61% 的圆内
os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
bg = work.resize((768, 768), Image.LANCZOS)
bg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_art.png"))
print("adaptive bg 768x768 saved")

# ------------------------------------------------------- 3. 旧版图标(裁剪+遮罩)
LEGACY_CROP = 0.70              # 旧版无安全区约束，再裁紧些让主体更大
ls = int(work.width * LEGACY_CROP)
lo = (work.width - ls) // 2
core = work.crop((lo, lo, lo + ls, lo + ls))

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 8                          # 超采样倍率，用于平滑圆角/圆边


def rounded_mask(size, radius_ratio):
    m = Image.new("L", (size * SS, size * SS), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size * SS - 1, size * SS - 1],
                        radius=int(size * SS * radius_ratio), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    m = Image.new("L", (size * SS, size * SS), 0)
    d = ImageDraw.Draw(m)
    d.ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


for name, size in DENSITIES.items():
    out_dir = os.path.join(RES, "mipmap-" + name)
    os.makedirs(out_dir, exist_ok=True)
    base = core.resize((size, size), Image.LANCZOS)

    sq = base.convert("RGBA")
    sq.putalpha(rounded_mask(size, 0.20))
    sq.save(os.path.join(out_dir, "ic_launcher.png"))

    rd = base.convert("RGBA")
    rd.putalpha(circle_mask(size))
    rd.save(os.path.join(out_dir, "ic_launcher_round.png"))
    print("mipmap-%s: %dpx  square+round" % (name, size))

# --------------------------------------------------------------- 4. 自检预览
pv = 512
vis_side = int(work.width * 72 / 108)     # 自适应图标可见区 = 中心 72/108
vleft = (work.width - vis_side) // 2
inner = work.crop((vleft, vleft, vleft + vis_side, vleft + vis_side)).resize(
    (pv, pv), Image.LANCZOS)
canvas = Image.new("RGB", (pv * 2 + 36, pv), (244, 245, 250))
masked = inner.convert("RGBA")
masked.putalpha(circle_mask(inner.width))
canvas.paste(inner, (0, 0))
canvas.paste(masked, (pv + 36, 0), masked)
canvas.save(os.path.join(VIBE, "preview_adaptive.png"))
print("preview ->", os.path.join(VIBE, "preview_adaptive.png"))
print("DONE")
