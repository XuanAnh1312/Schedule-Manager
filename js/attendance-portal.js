/* Issue #8: SQL Server attendance/report portal. Completely separate from demo localStorage. */
(() => {
  'use strict';
  const API = location.port === '8081' || location.port === '8082' ? '' : 'http://localhost:8081';
  const TZ = 'Asia/Ho_Chi_Minh';
  const $ = id => document.getElementById(id);
  let token = sessionStorage.getItem('attendance_jwt') || '';
  let me = null, records = [], shifts = [], currentPeriod = '';
  const safeNumber = v => v === null || v === undefined ? 'Chưa đủ dữ liệu' : String(v);
  const vietnamTime = text => new Date(text + ':00+07:00').toISOString();
  const fmtTime = v => v ? new Date(v).toLocaleString('vi-VN',{timeZone:TZ,day:'2-digit',month:'2-digit',year:'numeric',hour:'2-digit',minute:'2-digit'}) : '—';
  const el = (tag,text,cls) => {
    const n=document.createElement(tag);
    if(text!==undefined && text!==null)n.textContent=String(text);
    if(cls)n.className=cls;
    return n;
  };
  function message(text,error=false){$('status').textContent=text;$('status').className=error?'error':'success';}
  async function api(path,options={}) {
    const headers={'Accept':'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...(token?{'Authorization':`Bearer ${token}`}:{})};
    let res;
    try { res=await fetch(API+path,{...options,headers:{...headers,...options.headers},cache:'no-store'}); }
    catch { throw new Error('Không thể kết nối đến hệ thống chấm công. Vui lòng thử lại sau hoặc liên hệ quản trị viên.'); }
    if(!res.ok){
      let payload={};try{payload=await res.json()}catch{}
      if(res.status===401){token='';sessionStorage.removeItem('attendance_jwt');$('app-view').hidden=true;$('login-view').hidden=false;}
      throw new Error(`${res.status}: ${payload.error || res.statusText}`);
    }
    if(options.raw) return res;
    return res.status===204?null:res.json();
  }
  function table(id,items,fields,empty='Chưa có thông tin trong kỳ này'){
    const body=$(id);body.replaceChildren();
    if(!items.length){let row=el('tr');let cell=el('td',empty);cell.colSpan=fields.length;row.append(cell);body.append(row);return;}
    for(const item of items){let row=el('tr');for(const field of fields){row.append(el('td',typeof field==='function'?field(item):item[field]??'—'));}body.append(row);}
  }
  function refreshSelect(select,rows,label){const old=select.value;select.replaceChildren();
    if(!rows.length){select.append(new Option('Chưa có thông tin trong kỳ này',''));return;}
    for(const r of rows)select.append(new Option(label(r),r.id));
    if(rows.some(r=>r.id===old))select.value=old;
  }
  const btn = async (button,fn) => {
    button.disabled=true;
    try{await fn()}catch(error){if($('login-view').hidden)message(error.message,true);else $('login-message').textContent=error.message;}finally{button.disabled=false}
  };
  function paintDashboard(s) {
    const stats=[['Nhân viên đang hoạt động',s.employeeCount],['Ca đã công bố',s.publishedShifts],
      ['Ca thiếu người',s.understaffedShifts],['Đang làm',s.currentlyWorking],
      ['Chưa ghi nhận giờ vào hôm nay',s.notCheckedInToday],['Yêu cầu chờ duyệt',s.pendingRequests],
      ['Giờ đã phân công',s.scheduledHours],['Giờ mục tiêu',s.targetHours],['Giờ làm thực tế',s.actualHours],
      ['Giờ công đã duyệt',s.approvedHours],['Chênh lệch giờ',s.differenceHours],['Tỷ lệ đạt mục tiêu',s.percentage==null?'Chưa đủ dữ liệu':`${s.percentage}%`]];
    const parent=$('summary');parent.replaceChildren();
    for(const [title,value] of stats){if(value===undefined)continue;const card=el('article',null,'stat');
      card.append(el('span',title),el('strong',safeNumber(value)));parent.append(card);}
    $('last-updated').textContent=`Cập nhật lúc ${fmtTime(s.generatedAt)} · ${s.variant === 'published-only' ? 'Dữ liệu từ lịch đã công bố' : 'Phạm vi: ' + s.variant}`;
  }
  async function refreshAll() {
    const periods=await api('/api/reports/periods');
    refreshSelect($('period-select'),periods,p=>`${p.name} (${p.startDate} → ${p.endDate})`);
    currentPeriod=$('period-select').value;
    shifts=await api('/api/attendance/shifts');
    refreshSelect($('shift-select'),shifts,s=>`${s.name} · ${s.shift_date} · ${s.start_time}`);
    refreshSelect($('request-shift'),shifts,s=>`${s.name} · ${s.shift_date}`);
    if(!currentPeriod){$('summary').replaceChildren();$('report-body').replaceChildren();message('Chưa có kỳ báo cáo được thiết lập. Vui lòng liên hệ quản trị viên.',true);return;}
    const [dashboard,own,requests,audit]=await Promise.all([
      api('/api/reports/dashboard?periodId='+encodeURIComponent(currentPeriod)),
      api('/api/attendance/records?periodId='+encodeURIComponent(currentPeriod)),
      api('/api/attendance/requests?status=all'),
      api('/api/attendance/audit')
    ]);
    records=own;
    paintDashboard(dashboard);
    table('records-body',own,[r=>`${r.shift_name} (${r.shift_date}) · ${r.id}`,r=>fmtTime(r.clock_in_at),r=>fmtTime(r.clock_out_at),r=>r.worked_hours??'—','status']);
    const requestList=$('own-requests');requestList.replaceChildren();
    requestList.append(el('h3','Tình trạng các đề nghị chấm công'));
    if(!requests.length)requestList.append(el('p','Bạn chưa gửi đề nghị điều chỉnh nào.'));
    for(const r of requests){requestList.append(el('p',`${r.full_name} · ${r.status} · ${r.reason} · ${fmtTime(r.requested_at)}`,'audit'));}
    const log=$('audit-log');log.replaceChildren();log.append(el('h3','Nhật ký chấm công'));
    if(!audit.length)log.append(el('p','Chưa có hoạt động chấm công được ghi nhận.'));
    for(const e of audit.slice(0,30))log.append(el('p',`${fmtTime(e.occurred_at)} · ${e.action} · ${e.reason||'—'}`,'audit'));
    if(me.role==='admin'){
      const [users,shortages,pending,punches,anomalies]=await Promise.all([
        api('/api/reports/users?periodId='+encodeURIComponent(currentPeriod)),
        api('/api/reports/shortages?periodId='+encodeURIComponent(currentPeriod)),
        api('/api/attendance/requests?status=pending'),
        api('/api/attendance/pending-approval?periodId='+encodeURIComponent(currentPeriod)),
        api('/api/reports/attendance-status?periodId='+encodeURIComponent(currentPeriod))
      ]);
      table('report-body',users,['employeeCode','name','targetHours','scheduledHours','actualHours','approvedHours','differenceHours',
        u=>u.percent===null?'—':`${u.percent}%`,'progressLabel']);
      table('shortages-body',shortages,['name','date','required','assigned','reason']);
      table('anomalies-body',anomalies,['name','shiftName','status','lateMinutes']);
      const chart=$('hours-chart');chart.replaceChildren();
      if(!users.length)chart.append(el('p','Chưa có dữ liệu nhân viên trong kỳ này.'));
      for(const u of users){const row=el('div',null,'chart-row');
        const name=el('span',u.name),track=el('div',null,'chart-track');
        const bar=el('div',null,'chart-bar');
        const percent=u.percent===null?0:Math.max(0,Math.min(u.percent,200));
        bar.style.width=(percent/2)+'%';track.append(bar);
        const label=el('strong',`${u.scheduledHours}/${u.targetHours}h`);
        row.setAttribute('aria-label',`${u.name}: ${u.scheduledHours} giờ đã xếp / ${u.targetHours} giờ mục tiêu, ${safeNumber(u.percent)} phần trăm`);
        row.append(name,track,label);chart.append(row);
      }
      const punchBox=$('pending-punches');punchBox.replaceChildren();
      if(!punches.length)punchBox.append(el('p','Không có lượt chấm công nào đang chờ xác nhận.'));
      for(const p of punches){const box=el('div',null,'request');
        box.append(el('p',`${p.full_name} · ${p.shift_name} · ${fmtTime(p.clock_in_at)} → ${fmtTime(p.clock_out_at)}`));
        const wrap=el('div',null,'toolbar'),reason=el('input');reason.setAttribute('aria-label','Lý do xác nhận giờ công');reason.placeholder='Lý do xác nhận giờ công';
        const button=el('button','Xác nhận giờ công');
        button.onclick=()=>btn(button,async()=>{if(reason.value.trim().length<5)throw Error('Vui lòng nhập lý do có ít nhất 5 ký tự.');
          await api(`/api/attendance/${encodeURIComponent(p.id)}/approve`,{method:'POST',body:JSON.stringify({reason:reason.value})});await refreshAll();});
        wrap.append(reason,button);box.append(wrap);punchBox.append(box);
      }
      const pendingBox=$('pending-requests');pendingBox.replaceChildren();
      if(!pending.length)pendingBox.append(el('p','Không có đề nghị điều chỉnh đang chờ duyệt.'));
      for(const r of pending){const section=el('section',null,'request');
        section.append(el('p',`${r.full_name} · ${r.reason}`),
          el('p',`Đề xuất: ${fmtTime(r.proposed_in_at)} → ${fmtTime(r.proposed_out_at)}, nghỉ ${r.proposed_break_minutes} phút`),
          el('p',`Công gốc: ${r.before_json||'Chưa có lượt công'}`));
        const controls=el('div',null,'toolbar');const reason=el('input');reason.placeholder='Nhập lý do xét duyệt (ít nhất 5 ký tự)';
        reason.setAttribute('aria-label','Lý do duyệt hoặc từ chối');
        const approve=el('button','Duyệt');const reject=el('button','Từ chối','danger');
        approve.onclick=()=>review(r.id,true,reason.value,approve);
        reject.onclick=()=>review(r.id,false,reason.value,reject);
        controls.append(reason,approve,reject);section.append(controls);pendingBox.append(section);
      }
    }
    message('Dữ liệu chấm công và báo cáo đã được cập nhật.');
  }
  async function review(id,approve,reason,button){await btn(button,async()=>{
    if(reason.trim().length<5)throw Error('Vui lòng nhập lý do có ít nhất 5 ký tự.');
    await api(`/api/attendance/requests/${encodeURIComponent(id)}/review`,{method:'POST',body:JSON.stringify({approve,reason})});
    await refreshAll();
  });}
  function on(id,event,handler){$(id).addEventListener(event,handler);}
  on('login-form','submit',event=>{
    event.preventDefault();const form=event.currentTarget;const button=form.querySelector('button');
    btn(button,async()=>{
      $('login-message').textContent='';
      const data=Object.fromEntries(new FormData(form));
      const response=await api('/api/auth/login',{method:'POST',body:JSON.stringify(data)});
      token=response.token; // backend LoginResponse(token)
      if(!token)throw Error('Đăng nhập chưa thành công. Vui lòng thử lại hoặc liên hệ quản trị viên.');
      sessionStorage.setItem('attendance_jwt',token);
      await initSession();
      form.reset();
    });
  });
  async function initSession(){
    me=await api('/api/attendance/me');
    $('user-info').textContent=`${me.full_name} · ${me.role==='admin'?'Quản trị viên':'Nhân viên'}`;
    $('manager').hidden=me.role!=='admin';
    $('login-view').hidden=true;$('app-view').hidden=false;
    await refreshAll();
  }
  on('logout-btn','click',()=>{token='';me=null;sessionStorage.removeItem('attendance_jwt');$('app-view').hidden=true;$('login-view').hidden=false;});
  on('period-select','change',()=>btn($('refresh-btn'),refreshAll));
  on('refresh-btn','click',()=>btn($('refresh-btn'),refreshAll));
  on('checkin-btn','click',()=>btn($('checkin-btn'),async()=>{
    const shiftId=$('shift-select').value;if(!shiftId)throw Error('Bạn chưa có ca làm việc được phân công để chấm công.');
    await api('/api/attendance/check-in',{method:'POST',body:JSON.stringify({shiftId})});await refreshAll();
  }));
  on('checkout-btn','click',()=>btn($('checkout-btn'),async()=>{
    const open=records.find(r=>r.status==='open');if(!open)throw Error('Bạn chưa có lượt ghi nhận giờ vào cần kết thúc trong kỳ này.');
    const br=Number($('break-minutes').value);if(!Number.isInteger(br)||br<0)throw Error('Phút nghỉ không hợp lệ');
    await api(`/api/attendance/${encodeURIComponent(open.id)}/check-out`,{method:'POST',body:JSON.stringify({breakMinutes:br})});await refreshAll();
  }));
  on('request-form','submit',event=>{
    event.preventDefault();const form=event.currentTarget;const button=form.querySelector('button');
    btn(button,async()=>{
      const value=Object.fromEntries(new FormData(form));
      if(!value.shiftId)throw Error('Vui lòng chọn ca làm việc cần điều chỉnh.');
      const body={shiftId:value.shiftId,recordId:value.recordId||null,
        clockIn:vietnamTime(value.clockIn),clockOut:vietnamTime(value.clockOut),
        breakMinutes:Number(value.breakMinutes),reason:value.reason};
      await api('/api/attendance/requests',{method:'POST',body:JSON.stringify(body)});
      form.reset();await refreshAll();
    });
  });
  on('lock-form','submit',event=>{
    event.preventDefault();const button=event.currentTarget.querySelector('button');const reason=new FormData(event.currentTarget).get('reason');
    if(!confirm(`Bạn có chắc muốn khóa kỳ chấm công đang chọn? Sau khi khóa, mọi điều chỉnh cần được phê duyệt.`))return;
    btn(button,async()=>{await api(`/api/attendance/periods/${encodeURIComponent(currentPeriod)}/lock`,{method:'POST',body:JSON.stringify({reason})});await refreshAll();});
  });
  async function download(format,button){await btn(button,async()=>{
    if(!currentPeriod)throw Error('Vui lòng chọn kỳ báo cáo trước khi xuất dữ liệu.');
    const res=await api(`/api/reports/export?periodId=${encodeURIComponent(currentPeriod)}&format=${format}`,{raw:true});
    const blob=await res.blob();const link=document.createElement('a');const url=URL.createObjectURL(blob);
    link.href=url;link.download=`attendance-${currentPeriod}.${format}`;document.body.append(link);link.click();link.remove();
    setTimeout(()=>URL.revokeObjectURL(url),5000);
  });}
  on('csv-btn','click',()=>download('csv',$('csv-btn')));
  on('xlsx-btn','click',()=>download('xlsx',$('xlsx-btn')));
  if(token)initSession().catch(e=>{message(e.message,true);$('login-view').hidden=false;$('app-view').hidden=true;});
})();
