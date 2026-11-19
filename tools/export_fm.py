"""Convierte el BS-RoFormer voz masculina/femenina (aufr33) a ONNX para Ensayo de Voces.

Exporta solo el núcleo (sin STFT): entrada x [1, 801, 4100] (t, frecuencia·canal·re/im),
salida mask [1, 2, 801, 4100] (0 = masculina, 1 = femenina). Los pesos quedan en 16 bits.
La app hace la STFT, aplica la máscara y la iSTFT (RoformerEngine.java).
uso: python export_fm.py carpeta_modelos salida.onnx
"""
import sys, os, yaml, torch, numpy as np, onnx
from onnx import numpy_helper, helper, TensorProto
from audio_separator.separator.uvr_lib_v5.roformer.bs_roformer import BSRoformer

mdir, dst = sys.argv[1:]
cfg = yaml.load(open(os.path.join(mdir, 'config_chorus_male_female_bs_roformer.yaml')), Loader=yaml.FullLoader)
m = cfg['model']
for k in ['freqs_per_bands', 'multi_stft_resolutions_window_sizes']:
    m[k] = tuple(m[k])
m['flash_attn'] = False
net = BSRoformer(**m)
sd = torch.load(os.path.join(mdir, 'bs_roformer_male_female_by_aufr33_sdr_7.2889.ckpt'), map_location='cpu', weights_only=False)
sd = sd.get('state_dict', sd)
print(net.load_state_dict(sd, strict=True))
net.eval()


class Core(torch.nn.Module):
    def __init__(s, n):
        super().__init__(); s.n = n

    def forward(s, x):
        n = s.n
        x = n.band_split(x)
        for blk in n.layers:
            time_t, freq_t = blk[-2], blk[-1]
            b, t, f, d = x.shape
            # transformer en el tiempo, de a 8 bandas (menos memoria en el teléfono)
            x = x.permute(0, 2, 1, 3).reshape(b * f, t, d)
            nb = b * f; padn = (-nb) % 8
            xp = torch.cat([x, x[:padn]], dim=0) if padn else x
            x = torch.cat([time_t(xp[i:i + 8]) for i in range(0, nb + padn, 8)], dim=0)[:nb]
            x = x.reshape(b, f, t, d).permute(0, 2, 1, 3).reshape(b * t, f, d)
            x = freq_t(x)
            x = x.reshape(b, t, f, d)
        x = n.final_norm(x)
        return torch.stack([fn(x) for fn in n.mask_estimators], dim=1)


core = Core(net).eval()
x = torch.randn(1, 801, 4100)
tmp = dst + '.fp32.onnx'
with torch.no_grad():
    ref = core(x).numpy()   # también llena la caché de rotary: sin esto la exportación sale rota
    torch.onnx.export(core, (x,), tmp, input_names=['x'], output_names=['mask'], opset_version=17, dynamo=False)

# Pesos a 16 bits (se convierten a 32 al cargar): mitad de tamaño para bajar
mo = onnx.load(tmp)
g = mo.graph
casts, inits = [], []
for init in list(g.initializer):
    if init.data_type == TensorProto.FLOAT and np.prod(init.dims) >= 1024:
        h = numpy_helper.from_array(numpy_helper.to_array(init).astype(np.float16), init.name + '_h')
        inits.append(h)
        casts.append(helper.make_node('Cast', [h.name], [init.name], to=TensorProto.FLOAT, name=init.name + '_cast'))
        g.initializer.remove(init)
g.initializer.extend(inits)
nodes = casts + list(g.node)
del g.node[:]; g.node.extend(nodes)
onnx.save(mo, dst)
os.remove(tmp)

import onnxruntime as ort
so = ort.SessionOptions(); so.enable_cpu_mem_arena = False; so.enable_mem_pattern = False
y = ort.InferenceSession(dst, so, providers=['CPUExecutionProvider']).run(None, {'x': x.numpy()})[0]
err = np.abs(y - ref).mean() / np.abs(ref).mean()
print('error relativo', err, 'tamaño MB', os.path.getsize(dst) // 2**20)
assert err < 1e-3
