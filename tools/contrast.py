"""Contrast of every text pair in the theme (ui/theme). Run after changing a colour; every pair must print OK, or large-only where the text is 14 sp bold or bigger."""
import colorsys
def lum(h):
    r,g,b=[int(h[i:i+2],16)/255 for i in (0,2,4)]
    f=lambda c: c/12.92 if c<=0.03928 else ((c+0.055)/1.055)**2.4
    return 0.2126*f(r)+0.7152*f(g)+0.0722*f(b)
def cr(a,b):
    la,lb=lum(a),lum(b); hi,lo=max(la,lb),min(la,lb); return (hi+0.05)/(lo+0.05)
def hsl(h,s,l):
    r,g,b=colorsys.hls_to_rgb(h/360,l,s); return '%02X%02X%02X'%(round(r*255),round(g*255),round(b*255))
pairs = {
 # light
 'primary/onPrimary': ('178040','FFFFFF'),
 'hero start white': ('15854A','FFFFFF'), 'hero end white': ('0F7F86','FFFFFF'),
 'citrus on hero start': ('FFE08A','15854A'), 'citrus on hero end':('FFE08A','0F7F86'),
 'onBackground/background': ('171C18','F6F9F4'),
 'onSurfaceVariant/card': ('4A5449','FFFFFF'),
 'onSurfaceVariant/background': ('4A5449','F6F9F4'),
 'primary on card (links)': ('178040','FFFFFF'),
 'tertiary/white': ('B4432A','FFFFFF'), 'onPrimaryContainer/primaryContainer': ('05331A','C6F2D2'),
 'onTertiaryContainer/tertiaryContainer': ('3B0900','FFDCD0'),
 'warm call white': ('E2572F','FFFFFF'), 'warm call end white': ('C63A6E','FFFFFF'),
 # dark
 'dark primary/onPrimary': ('8FE0A8','003A1C'), 'dark onBackground/background': ('E2E8E1','121A15'),
 'dark onSurfaceVariant/card': ('C3CCC2','202A24'), 'dark primary on card': ('8FE0A8','202A24'),
 'dark hero white': ('0E6A3B','FFFFFF'), 'dark hero end white': ('0B6B70','FFFFFF'),
}
for k,(a,b) in pairs.items(): print('%-42s %s/%s %.2f %s'%(k,a,b,cr(a,b),'OK' if cr(a,b)>=4.5 else ('large-only' if cr(a,b)>=3 else 'FAIL')))
print('--- aisle light: content (icon-bg) vs container tint; and content text on white card')
for name,h,s in [('produce',128,.6),('bakery',34,.75),('meat',356,.65),('dairy',46,.8),('chilled',192,.6),('frozen',210,.7),('tins',18,.65),('pasta',40,.6),('world',268,.5),('baking',330,.6),('nuts',28,.4),('oils',68,.55),('spices',10,.75),('freefrom',168,.5),('drinks',228,.55),('household',200,.18),('other',120,.06)]:
    cont=hsl(h,s,0.93); strong=hsl(h,s,0.30); txt=hsl(h,s,0.26)
    print('%-10s tint %s strong %s  strong/tint %.2f  text/tint %.2f  onSurface/tint %.2f'%(name,cont,strong,cr(strong,cont),cr(txt,cont),cr('171C18',cont)))
print('--- aisle dark')
for name,h,s in [('produce',128,.6),('dairy',46,.8),('household',200,.18),('other',120,.06)]:
    cont=hsl(h,s*0.45,0.24); strong=hsl(h,s,0.80)
    print('%-10s tint %s strong %s strong/tint %.2f  onSurfaceDark/tint %.2f'%(name,cont,strong,cr(strong,cont),cr('E2E8E1',cont)))
