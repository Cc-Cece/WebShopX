#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REPLACEMENTS: dict[str, list[tuple[str, str]]] = {
    "src/main/java/com/webshopx/InventoryService.java": [
        ('if (source == InventorySource.ENDER_CHEST) return "末影箱第 " + (slot + 1) + " 格";',
         'if (source == InventorySource.ENDER_CHEST) return "Ender Chest #" + (slot + 1);'),
        ('if (slot < 9) return "快捷栏第 " + (slot + 1) + " 格";',
         'if (slot < 9) return "Hotbar #" + (slot + 1);'),
        ('if (slot < 36) return "背包第 " + (slot + 1) + " 格";',
         'if (slot < 36) return "Main Inventory #" + (slot + 1);'),
        ('if (slot == 40) return "副手";',
         'if (slot == 40) return "Offhand";'),
        ('return "装备栏第 " + (slot - 35) + " 格";',
         'return "Armor #" + (slot - 35);'),
    ],
    "src/main/java/com/webshopx/PlayerDataInventoryService.java": [
        ('return "末影箱第 " + (slot + 1) + " 格";',
         'return "Ender Chest #" + (slot + 1);'),
        ('return "快捷栏第 " + (slot + 1) + " 格";',
         'return "Hotbar #" + (slot + 1);'),
        ('return "背包第 " + (slot + 1) + " 格";',
         'return "Main Inventory #" + (slot + 1);'),
        ('return "副手";',
         'return "Offhand";'),
        ('return "装备栏第 " + (slot - 35) + " 格";',
         'return "Armor #" + (slot - 35);'),
    ],
    "src/main/java/com/webshopx/EmbeddedWebServer.java": [
        ('row.addProperty("source", "官方商城 · " + product.title());',
         'row.addProperty("sourceType", "OFFICIAL_STORE");\n'
         '          row.addProperty("sourceName", product.title());\n'
         '          row.addProperty("source", "Official Store · " + product.title());'),
        ('row.addProperty("source", "玩家 " + match.buyerName() + " 的收购单");',
         'row.addProperty("sourceType", "PLAYER_BUY_ORDER");\n'
         '        row.addProperty("sourceName", match.buyerName());\n'
         '        row.addProperty("source", "Player " + match.buyerName() + " Buy Order");'),
    ],
}


def main() -> int:
    changed = 0
    for relative, replacements in REPLACEMENTS.items():
        path = ROOT / relative
        source = path.read_text(encoding="utf-8")
        original = source
        for old, new in replacements:
            if old in source:
                source = source.replace(old, new)
            elif new not in source:
                raise RuntimeError(f"Expected i18n migration target was not found in {relative}: {old}")
        if source != original:
            path.write_text(source, encoding="utf-8")
            changed += 1
            print(f"updated {relative}")
    print(f"Java i18n normalization complete: {changed} file(s) changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
