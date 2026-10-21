#!/usr/bin/env python3
"""
Fake documents for testing the OCR + reader pipeline: an A4 page rendered
with a real font, then spoiled like a phone photo (tilt, uneven light,
blur, noise, JPEG). Writes 8-bit PGM files, the input of pensyocr-test.

    python3 sample.py OUTDIR
"""
import io
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

DOCS = {
    # name: list of (left text, right text or None, bold)
    "bolletta": [
        ("SMAT S.p.A.", None, True),
        ("Societa Metropolitana Acque Torino", None, False),
        ("Bolletta n. 2026/123456 del 02/10/2026", None, False),
        ("Periodo di fatturazione: dal 01/07/2026 al 30/09/2026", None, False),
        ("Servizio idrico integrato - fatturazione bimestrale", None, False),
        ("", None, False),
        ("Totale imponibile", "78,40", False),
        ("IVA 10%", "7,84", False),
        ("TOTALE DA PAGARE", "€ 86,24", True),
        ("Da pagare entro il", "05/11/2026", True),
        ("", None, False),
        ("Codice avviso pagoPA 3018 0000 1234 5678 90", None, False),
    ],
    "referto": [
        ("ASL Citta di Torino", None, True),
        ("Ospedale Maria Vittoria - Ambulatorio di Cardiologia", None, False),
        ("REFERTO DI VISITA CARDIOLOGICA", None, True),
        ("Data della visita: 14/10/2026", None, False),
        ("Paziente: Mario Rossi", None, False),
        ("Anamnesi: palpitazioni occasionali.", None, False),
        ("ECG: ritmo sinusale, nella norma.", None, False),
        ("Ecocardiogramma: funzione sistolica conservata.", None, False),
        ("Conclusioni: controllo tra 12 mesi.", None, False),
        ("Dott. Luca Bianchi", None, False),
    ],
    "bollo": [
        ("Regione Piemonte", None, True),
        ("Tassa automobilistica - avviso di scadenza", None, False),
        ("Targa AB123CD   Autovettura", None, False),
        ("Importo dovuto", "289,46 €", True),
        ("Scadenza", "31 gennaio 2027", True),
    ],
}


def render(lines, width=2100, height=2970):
    page = Image.new("L", (width, height), 250)
    d = ImageDraw.Draw(page)
    f = ImageFont.truetype(FONT, 46)
    fb = ImageFont.truetype(BOLD, 46)
    y = 220
    for left, right, bold in lines:
        font = fb if bold else f
        if left:
            d.text((160, y), left, fill=20, font=font)
        if right:
            w = d.textlength(right, font=font)
            d.text((width - 160 - w, y), right, fill=20, font=font)
        y += 88
    return page


def spoil(img, seed):
    rnd = random.Random(seed)
    img = img.rotate(rnd.uniform(-2.5, 2.5), resample=Image.BICUBIC, expand=True, fillcolor=90)
    w, h = img.size
    # uneven light: darker towards one corner
    grad = Image.linear_gradient("L").resize((w, h)).rotate(rnd.choice([30, 120, 210, 300]))
    img = Image.blend(img, Image.eval(grad, lambda v: int(v * 0.55)), 0.28)
    img = img.filter(ImageFilter.GaussianBlur(1.1))
    noise = Image.effect_noise((w, h), 14)
    img = Image.blend(img, noise, 0.08)
    # phone resolution and JPEG
    img = img.resize((int(w * 0.8), int(h * 0.8)), Image.BILINEAR)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=72)
    return Image.open(io.BytesIO(buf.getvalue())).convert("L")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    for i, (name, lines) in enumerate(DOCS.items()):
        img = spoil(render(lines), seed=i)
        img.save(os.path.join(out, name + ".pgm"))
        img.save(os.path.join(out, name + ".jpg"), quality=85)
        print(name, img.size)


if __name__ == "__main__":
    main()
