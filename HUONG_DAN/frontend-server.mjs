// Local frontend host. Proxy keeps JWT/API and the attendance iframe on one origin.
import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const port=Number(process.env.FRONTEND_PORT||8080);
const backend=new URL(process.env.BACKEND_URL||'http://127.0.0.1:8081');
if(!Number.isInteger(port)||port<1||port>65535)throw Error('FRONTEND_PORT must be 1..65535');
if(backend.protocol!=='http:'||!['127.0.0.1','localhost'].includes(backend.hostname)||backend.username||backend.password||backend.pathname!=='/')throw Error('BACKEND_URL must be a local HTTP origin');
const root=new URL('../',import.meta.url);
const publicFiles=new Map([
  ['/','index.html'],['/index.html','index.html'],['/workspace.html','workspace.html'],['/attendance.html','attendance.html'],
  ['/js/workspace.js','js/workspace.js'],['/js/attendance-portal.js','js/attendance-portal.js'],
  ['/css/workspace.css','css/workspace.css'],['/css/attendance.css','css/attendance.css']
]);
const server=http.createServer(async(req,res)=>{
  res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('X-Frame-Options','SAMEORIGIN');res.setHeader('Cache-Control','no-store');
  let path;try{path=new URL(req.url,'http://localhost').pathname;}catch{res.writeHead(400).end();return;}
  if(path.startsWith('/api/')){
    const headers={...req.headers,host:backend.host};delete headers.connection;
    // Browser origin checks end at this same-origin local host. Forwarding its
    // Origin to a different backend port would make Spring reject valid POSTs.
    delete headers.origin;
    const upstream=http.request({hostname:backend.hostname,port:backend.port||80,path:req.url,method:req.method,headers,timeout:30000},reply=>{
      res.writeHead(reply.statusCode,reply.headers);reply.pipe(res);reply.on('error',()=>res.destroy());
    });
    upstream.on('timeout',()=>upstream.destroy(Error('Backend timeout')));
    upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502,{'Content-Type':'application/json; charset=utf-8'}).end(JSON.stringify({error:'Backend chưa sẵn sàng. Hãy chạy CHAY_BACKEND.bat và kiểm tra cấu hình SQL Server.'}));else res.destroy();});
    req.on('aborted',()=>upstream.destroy());req.pipe(upstream);return;
  }
  const file=publicFiles.get(path);
  if(!file){res.writeHead(404).end();return;}
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405,{'Allow':'GET, HEAD'}).end();return;}
  try{
    const bytes=await readFile(fileURLToPath(new URL(file,root)));
    res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'text/html; charset=utf-8');
    res.writeHead(200);res.end(req.method==='HEAD'?undefined:bytes);
  }catch{res.writeHead(500).end('Cannot read frontend asset');}
});
server.on('error',error=>{console.error(error.code==='EADDRINUSE'?`Port ${port} is already in use.`:error.message);process.exitCode=1;});
server.listen(port,'127.0.0.1',()=>console.log(`Frontend http://localhost:${port} -> API ${backend.origin}`));
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>server.close());
