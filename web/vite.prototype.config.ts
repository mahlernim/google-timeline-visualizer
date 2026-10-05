import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { defineConfig, mergeConfig } from 'vite';
import base from './vite.config';

export default mergeConfig(base, defineConfig({
  plugins: [{
    name: 'local-prototype-evidence',
    configureServer(server) {
      server.middlewares.use(async (req,res,next) => {
        if (!req.url?.startsWith('/_prototype-output/')) return next();
        const name = req.url.slice('/_prototype-output/'.length);
        if (req.method !== 'POST' || req.headers.origin !== 'http://127.0.0.1:4173'
          || !/^(city|long|dateline)-(raster|vector)-(480|720|1080)(-(0|120|239))?\.(mp4|png|json)$/.test(name)) {
          res.statusCode=400; res.end(); return;
        }
        try {
          const chunks: Buffer[]=[]; let length=0;
          for await (const chunk of req) {
            length += chunk.length;
            if (length>50*1024*1024) throw new Error('Evidence exceeds limit');
            chunks.push(Buffer.from(chunk));
          }
          const directory=resolve(import.meta.dirname,'../app/build/vector-prototype-web');
          await mkdir(directory,{recursive:true});
          await writeFile(resolve(directory,name),Buffer.concat(chunks));
          res.end('saved');
        } catch { res.statusCode=500; res.end('Could not save prototype evidence'); }
      });
    },
  }],
}));
