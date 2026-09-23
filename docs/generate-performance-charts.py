"""BankingPJ 최종 실측값으로 README용 성능 그래프를 생성한다."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs" / "assets"
WIDTH, HEIGHT = 1400, 820
COLORS = {
    "background": "#F7F9FC",
    "surface": "#FFFFFF",
    "ink": "#172033",
    "muted": "#61708A",
    "grid": "#DDE4EF",
    "p95": "#2563EB",
    "p99": "#F97316",
}

LOCAL_READ = [(50, 15.634, 21.538), (100, 13.108, 16.883), (200, 12.916, 15.549)]
AWS_READ = [(50, 34.232, 511.127), (100, 23.197, 73.846), (200, 20.196, 174.959)]
LOCAL_TRANSACTIONS = [
    ("General", 46.365, 58.745),
    ("Hot Account", 70.631, 86.528),
    ("Idempotency", 45.386, 57.867),
]
AWS_TRANSACTIONS = [
    ("General", 44.213, 78.812),
    ("Hot Account", 36.918, 86.533),
    ("Idempotency", 18.882, 26.479),
]


# Windows 기본 글꼴을 사용하고 없으면 Pillow 기본 글꼴로 대체한다.
def font(size: int, bold: bool = False) -> ImageFont.ImageFont:
    name = "arialbd.ttf" if bold else "arial.ttf"
    path = Path("C:/Windows/Fonts") / name
    return ImageFont.truetype(str(path), size) if path.exists() else ImageFont.load_default()


# 공통 배경과 제목, 범례, 측정 범위 안내를 그린다.
def canvas(title: str, subtitle: str) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGB", (WIDTH, HEIGHT), COLORS["background"])
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((38, 34, WIDTH - 38, HEIGHT - 36), 28, fill=COLORS["surface"])
    draw.text((88, 78), title, fill=COLORS["ink"], font=font(42, True))
    draw.text((90, 136), subtitle, fill=COLORS["muted"], font=font(22))
    draw.rounded_rectangle((1040, 82, 1080, 102), 8, fill=COLORS["p95"])
    draw.text((1093, 77), "p95", fill=COLORS["ink"], font=font(22, True))
    draw.rounded_rectangle((1190, 82, 1230, 102), 8, fill=COLORS["p99"])
    draw.text((1243, 77), "p99", fill=COLORS["ink"], font=font(22, True))
    return image, draw


# y축 눈금과 격자를 실제 지연 범위에 맞춰 표시한다.
def axes(draw: ImageDraw.ImageDraw, max_value: float, ticks: int = 5) -> tuple[int, int, int, int]:
    left, top, right, bottom = 140, 210, 1300, 680
    draw.line((left, top, left, bottom), fill=COLORS["ink"], width=3)
    draw.line((left, bottom, right, bottom), fill=COLORS["ink"], width=3)
    for index in range(ticks + 1):
        value = max_value * index / ticks
        y = bottom - (bottom - top) * index / ticks
        draw.line((left, y, right, y), fill=COLORS["grid"], width=2)
        label = f"{value:.0f}"
        box = draw.textbbox((0, 0), label, font=font(18))
        draw.text((left - 24 - (box[2] - box[0]), y - 10), label, fill=COLORS["muted"], font=font(18))
    draw.text((66, 425), "ms", fill=COLORS["muted"], font=font(20, True))
    return left, top, right, bottom


# 읽기 목표 RPS별 p95와 p99를 독립된 선으로 시각화한다.
def draw_read_chart(filename: str, title: str, subtitle: str, rows: list[tuple[int, float, float]]) -> None:
    image, draw = canvas(title, subtitle)
    maximum = max(value for _, p95, p99 in rows for value in (p95, p99)) * 1.12
    left, top, right, bottom = axes(draw, maximum)
    x_positions = [left + 150, (left + right) // 2, right - 150]

    for metric_index, color in ((1, COLORS["p95"]), (2, COLORS["p99"])):
        points = []
        for x, row in zip(x_positions, rows):
            value = row[metric_index]
            y = bottom - (value / maximum) * (bottom - top)
            points.append((x, y))
        draw.line(points, fill=color, width=7, joint="curve")
        for (x, y), row in zip(points, rows):
            value = row[metric_index]
            draw.ellipse((x - 10, y - 10, x + 10, y + 10), fill=color, outline="white", width=3)
            label = f"{value:.3f}"
            box = draw.textbbox((0, 0), label, font=font(18, True))
            draw.text((x - (box[2] - box[0]) / 2, y - 40), label, fill=color, font=font(18, True))

    for x, row in zip(x_positions, rows):
        label = f"{row[0]} RPS"
        box = draw.textbbox((0, 0), label, font=font(20, True))
        draw.text((x - (box[2] - box[0]) / 2, bottom + 24), label, fill=COLORS["ink"], font=font(20, True))
    draw.text((90, 748), "Measured latency percentiles; HTTP failure 0%, checks 100%, dropped iterations 0.",
              fill=COLORS["muted"], font=font(18))
    image.save(OUTPUT / filename, optimize=True)


# 쓰기 시나리오의 p95와 p99를 그룹 막대로 표시한다.
def draw_transaction_chart(filename: str, title: str, subtitle: str,
                           rows: list[tuple[str, float, float]]) -> None:
    image, draw = canvas(title, subtitle)
    maximum = max(value for _, p95, p99 in rows for value in (p95, p99)) * 1.18
    left, top, right, bottom = axes(draw, maximum)
    centers = [left + 190, (left + right) // 2, right - 190]
    bar_width = 74

    for center, row in zip(centers, rows):
        for offset, value, color in ((-bar_width - 6, row[1], COLORS["p95"]), (6, row[2], COLORS["p99"])):
            x1 = center + offset
            x2 = x1 + bar_width
            y = bottom - (value / maximum) * (bottom - top)
            draw.rounded_rectangle((x1, y, x2, bottom), 10, fill=color)
            label = f"{value:.3f}"
            box = draw.textbbox((0, 0), label, font=font(17, True))
            draw.text(((x1 + x2) / 2 - (box[2] - box[0]) / 2, y - 30), label, fill=color, font=font(17, True))
        box = draw.textbbox((0, 0), row[0], font=font(20, True))
        draw.text((center - (box[2] - box[0]) / 2, bottom + 24), row[0], fill=COLORS["ink"], font=font(20, True))

    draw.text((90, 748), "General, Hot Account, and Idempotency use different workload shapes; compare latency only in context.",
              fill=COLORS["muted"], font=font(18))
    image.save(OUTPUT / filename, optimize=True)


# 네 개의 문서용 PNG를 동일한 스타일로 생성한다.
def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    draw_read_chart(
        "performance-local-read.png",
        "Local Read Throughput",
        "LARGE dataset | Target RPS vs latency percentiles",
        LOCAL_READ,
    )
    draw_read_chart(
        "performance-aws-read.png",
        "AWS Deployment E2E Read",
        "CloudFront to EC2/Nginx to Spring to RDS | Target RPS vs latency percentiles",
        AWS_READ,
    )
    draw_transaction_chart(
        "performance-local-transactions.png",
        "Local Transaction Scenarios",
        "General 20 RPS | Hot Account 10 RPS | Idempotency Replay 20 RPS",
        LOCAL_TRANSACTIONS,
    )
    draw_transaction_chart(
        "performance-aws-transactions.png",
        "AWS Deployment E2E Transactions",
        "General 20 RPS | Hot Account 10 RPS | Idempotency Replay 20 RPS",
        AWS_TRANSACTIONS,
    )


if __name__ == "__main__":
    main()
