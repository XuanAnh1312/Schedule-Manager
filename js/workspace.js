/* API-backed workspace. No business records are persisted in browser storage. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const state = {token:sessionStorage.getItem('attendance_jwt') || '', me:null, periods:[], employees:[], route:'periods', periodId:'', planId:'', filters:{}};
  let renderSequence = 0;
  const labels = {draft:'Nháp',open:'Đang mở',locked:'Đã khóa',archived:'Đã lưu trữ',pending_approval:'Chờ duyệt',approved:'Đã duyệt',published:'Đã công bố',superseded:'Đã thay thế',cancelled:'Đã hủy',pending:'Chờ xử lý',pending_peer:'Chờ đồng nghiệp',rejected:'Từ chối',peer_declined:'Đồng nghiệp từ chối',full:'Đủ người',understaffed:'Thiếu người',below_target:'Chưa đạt nhu cầu',submitted:'Đã gửi',completed:'Đã kết thúc'};
  const text = value => value == null ? '—' : labels[value] || String(value);
  const el = (tag, value, cls) => {const n=document.createElement(tag);if(value!=null)n.textContent=text(value);if(cls)n.className=cls;return n;};
  const admin = () => state.me?.role === 'admin';
  const day = () => new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Ho_Chi_Minh',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  const url = (path,params={}) => path+'?'+new URLSearchParams(Object.entries(params).filter(([,v])=>v!==''&&v!=null));
  const notice = (message,error=false) => {$('notice').textContent=message;$('notice').className=error?'error':'';};
  async function api(path,method='GET',body,raw=false) {
    let response;
    try { response=await fetch(path,{method,cache:'no-store',headers:{Accept:'application/json',...(state.token?{Authorization:'Bearer '+state.token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})}); }
    catch {throw new Error('Không thể kết nối hệ thống. Kiểm tra kết nối rồi thử lại.');}
    if(!response.ok){let error={};try{error=await response.json();}catch{}
      if(response.status===401){logout();throw new Error('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');}
      const details=error.details||error.fieldErrors||[];
      throw new Error((error.message||error.error||'Không thể thực hiện thao tác')+(Array.isArray(details)&&details.length?'\n'+details.map(d=>typeof d==='string'?d:JSON.stringify(d)).join('\n'):''));
    }
    return raw?response:response.status===204?null:response.json();
  }
  function button(label,fn,secondary=true) {const b=el('button',label,secondary?'secondary':'');b.type='button';b.onclick=async()=>{b.disabled=true;try{await fn();}catch(e){notice(e.message,true);}finally{b.disabled=false;}};return b;}
  function panel(title){const n=el('section',null,'panel');if(title)n.append(el('h2',title));$('content').append(n);return n;}
  function table(parent,rows,columns,actions) {
    if(!rows.length){parent.append(el('p','Chưa có dữ liệu phù hợp.','empty'));return;}
    const wrap=el('div',null,'table-wrap'),t=el('table'),head=el('tr');
    columns.forEach(([label])=>head.append(el('th',label)));if(actions)head.append(el('th','Thao tác'));
    const thead=el('thead');thead.append(head);t.append(thead);const body=el('tbody');
    for(const row of rows){const tr=el('tr');for(const [,value]of columns){tr.append(el('td',typeof value==='function'?value(row):row[value]));}if(actions){const td=el('td'),bar=el('div',null,'actions');actions(row).filter(Boolean).forEach(b=>bar.append(b));td.append(bar);tr.append(td);}body.append(tr);}
    t.append(body);wrap.append(t);parent.append(wrap);
  }
  function metrics(parent,entries){const grid=el('div',null,'cards');for(const [name,value]of entries){const n=el('article',null,'metric');n.append(el('span',name),el('strong',value));grid.append(n);}parent.append(grid);}
  function field(def,values={}) {
    const label=el('label',def.label),input=el(def.type==='textarea'?'textarea':def.options?'select':'input');input.name=def.key;
    if(def.options){if(!def.required&&!def.multiple)input.append(new Option('Không chọn',''));for(const option of def.options){const [id,name]=Array.isArray(option)?option:[option,option];input.append(new Option(text(name),id));}if(def.multiple)input.multiple=true;}
    else input.type=def.type||'text';
    if(def.min!=null)input.min=def.min;if(def.max!=null)input.max=def.max;if(def.step)input.step=def.step;
    input.required=!!def.required;input.disabled=!!def.disabled;
    const value=values[def.key]??def.value??'';
    if(def.type==='checkbox')input.checked=!!value;else if(def.multiple){for(const option of input.options)option.selected=(value||[]).includes(option.value);}else input.value=value;
    label.append(input);if(def.help)label.append(el('small',def.help));return {label,input};
  }
  function edit(title,definitions,values,save,saveLabel='Lưu') {
    const dialog=$('editor'),form=$('editor-form');$('editor-title').textContent=title;$('editor-fields').replaceChildren();$('editor-error').textContent='';$('editor-save').textContent=saveLabel;
    const inputs=definitions.map(def=>{const f=field(def,values);$('editor-fields').append(f.label);return [def,f.input];});
    form.onsubmit=async event=>{event.preventDefault();$('editor-save').disabled=true;$('editor-error').textContent='';
      try {const payload={};for(const [def,input]of inputs){payload[def.key]=def.type==='checkbox'?input.checked:def.multiple?[...input.selectedOptions].map(o=>o.value):def.type==='number'?(input.value===''?null:Number(input.value)):input.value||null;}
        await save(payload);dialog.close();await render();notice('Đã lưu thay đổi.');
      }catch(e){$('editor-error').textContent=e.message;}finally{$('editor-save').disabled=false;}
    };
    dialog.showModal();
  }
  $('editor-cancel').onclick=()=>$('editor').close();
  function periodPicker(parent,onChange=render){const bar=el('div',null,'toolbar');const f=field({key:'period',label:'Kỳ làm việc',options:state.periods.map(p=>[p.id,p.name+' · '+text(p.status)]),required:true},{period:state.periodId});f.input.onchange=()=>{state.periodId=f.input.value;state.planId='';onChange();};bar.append(f.label);parent.append(bar);return bar;}
  const currentPeriod = () => state.periods.find(p=>p.id===state.periodId);
  const employees = () => state.employees.filter(e=>e.status!=='inactive').map(e=>[e.id,e.fullName+' · '+e.employeeCode]);
  const periodFields = [
    {key:'name',label:'Tên kỳ',required:true},{key:'cycle',label:'Chu kỳ',options:[['weekly','Tuần'],['monthly','Tháng'],['yearly','Năm']],required:true},
    {key:'startDate',label:'Ngày bắt đầu',type:'date',required:true},{key:'endDate',label:'Ngày kết thúc',type:'date',required:true},
    {key:'openAt',label:'Mở đăng ký lúc',type:'datetime-local'},{key:'deadline',label:'Hạn đăng ký',type:'datetime-local'},
    {key:'autoLock',label:'Tự động khóa khi hết hạn',type:'checkbox',value:true},{key:'allowReopenByAdmin',label:'Cho phép mở lại có kiểm soát',type:'checkbox',value:true},
    {key:'requiredDaysOfWeek',label:'Ngày cần đăng ký',multiple:true,options:[[1,'Thứ hai'],[2,'Thứ ba'],[3,'Thứ tư'],[4,'Thứ năm'],[5,'Thứ sáu'],[6,'Thứ bảy'],[0,'Chủ nhật']]},
    {key:'exemptDates',label:'Ngày miễn đăng ký',help:'Các ngày YYYY-MM-DD, cách nhau bằng dấu phẩy'},{key:'closedDates',label:'Ngày đóng',help:'Các ngày YYYY-MM-DD, cách nhau bằng dấu phẩy'},
    {key:'applicableUserIds',label:'Nhân viên áp dụng',multiple:true,options:[]}];
  const split = value => (value||'').split(',').map(v=>v.trim()).filter(Boolean);
  function editPeriod(p={}){const defs=periodFields.map(f=>f.key==='applicableUserIds'?{...f,options:employees(),help:'Không chọn: áp dụng mọi nhân viên đang hoạt động.'}:f);
    edit(p.id?'Sửa kỳ làm việc':'Tạo kỳ làm việc',defs,{...p,exemptDates:(p.exemptDates||[]).join(','),closedDates:(p.closedDates||[]).join(','),requiredDaysOfWeek:(p.requiredDaysOfWeek||[]).map(String)},async v=>{
      v.requiredDaysOfWeek=v.requiredDaysOfWeek.map(Number);v.exemptDates=split(v.exemptDates);v.closedDates=split(v.closedDates);if(p.id)v.version=p.version;
      await api('/api/periods'+(p.id?'/'+p.id:''),p.id?'PUT':'POST',v);
    });
  }
  async function periodsScreen(){const root=panel();if(admin())root.append(button('Tạo kỳ',()=>editPeriod(),false));
    table(root,state.periods,[['Tên kỳ','name'],['Thời gian',p=>p.startDate+' → '+p.endDate],['Đăng ký','statusLabel'],['Phiên bản','version']],p=>[
      admin()&&p.status==='draft'&&button('Sửa',()=>editPeriod(p)),
      admin()&&p.status==='draft'&&button('Mở đăng ký',async()=>{await api('/api/periods/'+p.id+'/open','POST',{version:p.version});await render();}),
      admin()&&p.status==='open'&&button('Khóa đăng ký',async()=>{await api('/api/periods/'+p.id+'/lock','POST',{version:p.version});await render();}),
      admin()&&p.status==='locked'&&button('Mở lại',()=>edit('Mở lại kỳ',[{key:'reason',label:'Lý do',required:true},{key:'newDeadline',label:'Hạn mới',type:'datetime-local',required:p.autoLock},{key:'confirm',label:'Xác nhận mở lại kỳ',type:'checkbox',required:true}],{},v=>api('/api/periods/'+p.id+'/reopen','POST',{...v,version:p.version}),'Xác nhận')),
      admin()&&p.status==='locked'&&button('Lưu trữ',async()=>{if(confirm('Lưu trữ kỳ này? Các phương án chưa hoàn tất sẽ chặn thao tác.')){await api('/api/periods/'+p.id+'/archive','POST',{version:p.version});await render();}}),
      admin()&&button('Tình trạng đăng ký',async()=>{state.periodId=p.id;await availabilityScreen(true);})]);
  }
  async function availabilityScreen(progressOnly=false){$('content').replaceChildren();const root=panel();periodPicker(root);if(!state.periodId)return;
    if(admin()||progressOnly){const [progress,summary]=await Promise.all([api(url('/api/availability/progress',{periodId:state.periodId})),api(url('/api/availability/summary',{periodId:state.periodId}))]);
      metrics(root,[['Áp dụng',progress.applicable],['Đã gửi',progress.submitted],['Lưu nháp',progress.draft],['Chưa đăng ký',progress.notStarted]]);
      table(root,progress.employees,[['Nhân viên','fullName'],['Trạng thái','status']]);table(root,summary.days,[['Ngày','date'],['Người rảnh','availableEmployees'],['Người không thể làm','unavailableEmployees'],['Tổng giờ rảnh','availableHours']]);return;}
    const data=await api(url('/api/availability/my',{periodId:state.periodId}));root.append(el('p',data.statusLabel));
    const entries=el('div'),rows=[];root.append(entries);
    function row(date='',start='08:00',end='17:00',type='available'){
      const node=el('div',null,'availability-row'),fields=[field({key:'date',label:'Ngày',type:'date',required:true},{date}),field({key:'start',label:'Từ',type:'time',required:true},{start}),field({key:'end',label:'Đến',type:'time',required:true},{end}),field({key:'type',label:'Loại',options:[['available','Rảnh'],['unavailable','Không thể làm']]},{type})];fields.forEach(f=>{f.input.disabled=!data.editable;node.append(f.label);});
      const item={node,fields};rows.push(item);if(data.editable)node.append(button('Xóa',()=>{node.remove();rows.splice(rows.indexOf(item),1);}));entries.append(node);
    }
    for(const entry of data.registration?.entries||[])for(const slot of entry.slots||[])row(entry.date,slot.start,slot.end,slot.type);
    if(!rows.length&&data.editable)row(currentPeriod().startDate);
    if(data.editable){const bar=el('div',null,'toolbar');bar.append(button('Thêm khoảng giờ',()=>row(currentPeriod().startDate)));
      const save=async submit=>{const grouped=new Map();for(const r of rows){if(!r.fields.every(f=>f.input.reportValidity()))return;const [date,start,end,type]=r.fields.map(f=>f.input.value);if(!grouped.has(date))grouped.set(date,[]);grouped.get(date).push({start,end,type});}
        await api(url('/api/availability/my'+(submit?'/submit':''),{periodId:state.periodId}),submit?'POST':'PUT',{entries:[...grouped].map(([date,slots])=>({date,slots}))});await render();notice(submit?'Đã gửi đăng ký.':'Đã lưu đăng ký.');};
      bar.append(button('Lưu nháp',()=>save(false)),button('Gửi đăng ký',()=>save(true),false));root.append(bar);}
  }
  async function rulesScreen(){const root=panel();const catalog=await api('/api/rules/catalog');
    if(!admin()){const rules=await api('/api/rules/effective');metrics(root,[['Mục tiêu (giờ)',rules.derived.targetHours],['Trần (giờ)',rules.derived.maxHours]]);table(root,rules.values,[['Quy tắc','label'],['Giá trị','value'],['Đơn vị','unit'],['Nguồn',v=>v.source.scopeType]]);table(root,rules.missing,[['Cần cấu hình','label'],['Lý do','reason']]);return;}
    const settings=await api('/api/rules/settings');
    async function configure(){const defs=[{key:'scopeType',label:'Phạm vi',options:catalog.scopes.filter(s=>s.available).map(s=>[s.scopeType,({company:'Công ty',user:'Cá nhân',branch:'Chi nhánh',group:'Nhóm',employee_type:'Loại nhân viên'})[s.scopeType]])},{key:'scopeId',label:'Nhân viên',options:employees(),help:'Chỉ chọn khi áp dụng cho cá nhân.'},{key:'effectiveFrom',label:'Ngày hiệu lực',type:'date',value:day(),required:true},{key:'reason',label:'Lý do thay đổi',required:true}];
      const scopeOptions=[...employees()];for(const [type,label]of [['locations','Chi nhánh'],['work_groups','Nhóm'],['employee_types','Loại nhân viên']]){const rows=await api('/api/schedule/catalog/'+type);rows.forEach(r=>scopeOptions.push([r.id,label+': '+r.name]));}defs[1]={...defs[1],label:'Đối tượng áp dụng',options:scopeOptions,help:'Bỏ trống khi cấu hình toàn công ty.'};
      const values={};for(const p of catalog.parameters.filter(p=>p.valueType!=='level_pairs')){const s=settings.find(s=>s.paramKey===p.key&&s.scopeType==='company');if(s)values[p.key]=s.value;defs.push({key:p.key,label:p.label+(p.unit?' ('+p.unit+')':''),type:p.valueType==='enum'?'text':'number',...(p.allowedValues?.length?{options:p.allowedValues}:{}),min:p.min,max:p.max,step:'0.01',help:p.description});}
      edit('Cấu hình quy tắc',defs,values,async v=>{const items=catalog.parameters.filter(p=>v[p.key]!=null&&v[p.key]!=='').map(p=>({paramKey:p.key,scopeType:p.companyOnly?'company':v.scopeType,scopeId:p.companyOnly||v.scopeType==='company'?null:v.scopeId,value:String(v[p.key]),effectiveFrom:v.effectiveFrom}));
        const preview=await api('/api/rules/changes','POST',{reason:v.reason,items});showRulePreview(preview);},'Xem trước');
    }
    root.append(button('Thay đổi quy tắc',configure,false),button('Mẫu đề xuất',async()=>{const template=await api('/api/rules/templates');const preview=await api('/api/rules/changes','POST',{reason:'Xem xét mẫu đề xuất',items:template.items});showRulePreview(preview);await render();}));
    function showRulePreview(preview){state.rulePreview=preview;}
    root.append(button('Level thay thế',async()=>{const levels=await api('/api/levels');edit('Thêm quy tắc thay thế Level',[{key:'higherLevelId',label:'Level được làm thay',options:levels.map(l=>[l.id,l.name]),required:true},{key:'lowerLevelId',label:'Level yêu cầu của vị trí',options:levels.map(l=>[l.id,l.name]),required:true},{key:'reason',label:'Lý do',required:true}],{},async v=>{const existing=settings.find(s=>s.paramKey==='level_substitutions'&&s.scopeType==='company');const pairs=existing?JSON.parse(existing.value):[];pairs.push({higherLevelId:v.higherLevelId,lowerLevelId:v.lowerLevelId});const preview=await api('/api/rules/changes','POST',{reason:v.reason,items:[{paramKey:'level_substitutions',scopeType:'company',value:JSON.stringify(pairs)}]});showRulePreview(preview);},'Xem trước');}));
    table(root,settings,[['Quy tắc',s=>catalog.parameters.find(p=>p.key===s.paramKey)?.label||s.paramKey],['Giá trị',s=>s.paramKey==='level_substitutions'?'Đã cấu hình '+JSON.parse(s.value).length+' cặp':s.value],['Phạm vi','scopeType'],['Hiệu lực','effectiveFrom']],s=>[button('Bỏ giá trị',()=>edit('Bỏ quy tắc',[{key:'reason',label:'Lý do',required:true}],{},async v=>{const preview=await api('/api/rules/changes','POST',{reason:v.reason,items:[{op:'remove',paramKey:s.paramKey,scopeType:s.scopeType,scopeId:s.scopeId}]});showRulePreview(preview);},'Xem trước'))]);
    const history=await api('/api/rules/changes?status=all');table(panel('Lịch sử cấu hình'),history,[['Thời điểm','createdAt'],['Lý do','reason'],['Trạng thái','status']]);
    if(state.rulePreview){const preview=state.rulePreview,p=panel('Xem trước tác động');metrics(p,[['Nhân viên ảnh hưởng',preview.affectedUsers],['Còn thiếu cấu hình',preview.notReadyAfter]]);table(p,preview.companyChanges,[['Tham số','key'],['Trước','before'],['Sau','after']]);for(const w of preview.warnings||[])p.append(el('p',w));
      p.append(button('Xác nhận áp dụng',async()=>{if(!confirm('Áp dụng cấu hình đã xem trước? Lịch đã công bố được giữ nguyên.'))return;await api('/api/rules/changes/'+preview.changeId+'/apply','POST',{confirm:true});state.rulePreview=null;await render();notice('Đã áp dụng quy tắc.');},false));p.scrollIntoView({block:'nearest'});}
  }
  async function shiftsScreen(){const root=panel();periodPicker(root);const p=currentPeriod();if(!p)return;
    const [rows,levels,locations,skills,certificates,positions]=await Promise.all([api(url('/api/schedule/shifts',{periodId:p.id,from:p.startDate,to:p.endDate,includeCancelled:true})),api('/api/levels'),...['locations','skills','certificates','positions'].map(t=>api('/api/schedule/catalog/'+t))]);
    function configure(row){const s=row?.shift||{};const defs=[{key:'name',label:'Tên ca',required:true},{key:'date',label:'Ngày',type:'date',required:true,value:p.startDate},{key:'startTime',label:'Bắt đầu',type:'time',required:true},{key:'endTime',label:'Kết thúc',type:'time',required:true,help:'Kết thúc trước giờ bắt đầu: ca qua đêm.'},{key:'breakMinutes',label:'Nghỉ trong ca (phút)',type:'number',min:0,value:0},{key:'mode',label:'Cách phân ca',options:[['combined','Kết hợp'],['level','Theo Level'],['user','Theo nhân viên']],value:'combined'},
      {key:'totalRequired',label:'Số người cần',type:'number',min:1,required:true},{key:'minRequired',label:'Tối thiểu',type:'number',min:1},{key:'maxAllowed',label:'Tối đa',type:'number',min:1},{key:'locationId',label:'Địa điểm',options:locations.map(l=>[l.id,l.name])},
      ...[['mandatoryUserIds','Nhân viên bắt buộc',employees()],['preferredUserIds','Nhân viên ưu tiên',employees()],['skillIds','Kỹ năng',skills.map(x=>[x.id,x.name])],['certificateIds','Chứng chỉ',certificates.map(x=>[x.id,x.name])],['positionIds','Chức vụ',positions.map(x=>[x.id,x.name])]].map(([key,label,options])=>({key,label,options,multiple:true})),
      ...levels.map(l=>({key:'level_'+l.id,label:'Số người '+(l.name||l.id),type:'number',min:0})),{key:'note',label:'Ghi chú',type:'textarea'},...(!row?[{key:'additionalDates',label:'Tạo thêm cùng cấu hình vào các ngày',help:'YYYY-MM-DD, cách nhau bằng dấu phẩy. Để trống nếu chỉ tạo một ca.'},{key:'emergencyReason',label:'Lý do ca khẩn cấp',help:'Chỉ nhập khi đây là ca khẩn cấp.'}]:[])];
      const values={...s};for(const [id,count]of Object.entries(s.levelRequirements||{}))values['level_'+id]=count;
      edit(row?'Sửa ca':'Tạo ca',defs,values,async v=>{v.levelRequirements={};for(const l of levels){if(v['level_'+l.id]>0)v.levelRequirements[l.id]=v['level_'+l.id];delete v['level_'+l.id];}v.periodId=p.id;const additional=split(v.additionalDates),reason=v.emergencyReason;delete v.additionalDates;delete v.emergencyReason;if(!row&&reason)await api('/api/schedule/shifts/emergency','POST',{reason,shift:v});else if(!row&&additional.length)await api('/api/schedule/shifts/bulk','POST',{shifts:[v,...additional.map(date=>({...v,date}))]});else await api('/api/schedule/shifts'+(row?'/'+row.id:''),row?'PUT':'POST',row?{expectedVersion:row.version,shift:v}:v);});
    }
    root.append(button('Tạo ca',()=>configure(),false));
    table(root,rows,[['Ngày',r=>r.shift.date],['Ca',r=>r.shift.name],['Giờ',r=>r.shift.startTime+' → '+r.shift.endTime],['Nghỉ (phút)',r=>r.shift.breakMinutes],['Nhu cầu',r=>r.shift.totalRequired],['Trạng thái','status']],r=>[
      !r.cancelledAt&&button('Sửa',()=>configure(r)),button('Sao chép',()=>edit('Sao chép ca',[{key:'dates',label:'Các ngày',required:true,help:'YYYY-MM-DD, cách nhau bằng dấu phẩy'}],{},v=>api('/api/schedule/shifts/'+r.id+'/clone','POST',{dates:split(v.dates)}))),
      !r.cancelledAt&&button('Lặp ca',()=>edit('Lặp ca',[{key:'until',label:'Đến ngày',type:'date',required:true},{key:'weekdays',label:'Thứ trong tuần',options:[[1,'Hai'],[2,'Ba'],[3,'Tư'],[4,'Năm'],[5,'Sáu'],[6,'Bảy'],[7,'Chủ nhật']],multiple:true,required:true},{key:'everyNWeeks',label:'Mỗi bao nhiêu tuần',type:'number',min:1,value:1}],{},v=>api('/api/schedule/shifts/'+r.id+'/repeat','POST',{...v,weekdays:v.weekdays.map(Number)}))),
      !r.cancelledAt&&button('Hủy ca',()=>edit('Hủy ca',[{key:'reason',label:'Lý do hủy',required:true}],{},v=>api('/api/schedule/shifts/'+r.id+'/cancel','POST',{...v,expectedVersion:r.version}))) ]);
  }
  async function plansScreen(){const root=panel();periodPicker(root);const p=currentPeriod();if(!p)return;
    const plans=await api(url('/api/schedule/plans',{periodId:p.id}));
    root.append(button('Chạy thử',async()=>{const r=await api('/api/schedule/plans/dry-run','POST',{periodId:p.id,replaceExisting:false});engineResult(panel('Kết quả chạy thử'),r.result);},false),button('Lưu phương án mới',()=>edit('Tạo phương án',[{key:'name',label:'Tên phương án',required:true},{key:'replaceExisting',label:'Thay phân công hiện tại trong kỳ',type:'checkbox'}],{},v=>api('/api/schedule/plans','POST',{...v,periodId:p.id}))));
    if(plans.length>1){const bar=el('div',null,'toolbar');const base=field({key:'base',label:'Phương án gốc',options:plans.map(x=>[x.id,x.name])}),other=field({key:'other',label:'So sánh với',options:plans.map(x=>[x.id,x.name])});bar.append(base.label,other.label,button('So sánh',async()=>{const diff=await api(url('/api/schedule/plans/compare',{base:base.input.value,other:other.input.value}));const box=panel('So sánh phương án');metrics(box,[['Thêm',diff.added],['Bỏ',diff.removed],['Giữ nguyên',diff.unchanged]]);table(box,diff.addedAssignments||[],[['Ca','shiftId'],['Nhân viên','userId']]);table(box,diff.removedAssignments||[],[['Ca','shiftId'],['Nhân viên','userId']]);}));root.append(bar);}
    table(root,plans,[['Phương án','name'],['Trạng thái','status'],['Phiên bản','version'],['Số lần chạy','runCount']],r=>[button('Chi tiết',()=>planDetail(r.id)),r.status==='draft'&&button('Chạy lại',async()=>{await api('/api/schedule/plans/'+r.id+'/rerun','POST',{expectedVersion:r.version});await render();}),r.status==='draft'&&button('Hủy',()=>edit('Hủy phương án',[{key:'reason',label:'Lý do',required:true}],{},v=>api('/api/schedule/plans/'+r.id+'/cancel','POST',{...v,expectedVersion:r.version})))]);
  }
  function engineResult(root,result){if(!result)return;const summary=result.summary;metrics(root,[['Số ca',summary.totalShifts],['Đủ người',summary.full],['Thiếu người',summary.understaffed],['Phân công',summary.plannedAssignments]]);
      table(root,result.shifts||[],[['Ca','name'],['Ngày','date'],['Độ phủ','coverage'],['Cần','required'],['Đã xếp','filled']]);
    for(const shift of result.shifts||[]){if(shift.warnings?.length)root.append(el('p',shift.name+': '+shift.warnings.join('; ')));for(const d of shift.decisions||[])root.append(el('p',(d.employeeName||d.userId)+': '+(d.reasons||[]).join('; '),'muted'));for(const r of shift.rejections||[])root.append(el('p',(r.employeeName||r.userId)+': '+r.violations.map(v=>v.message).join('; '),'muted'));}
    table(root,result.missingParameters||[],[['Nhân viên','userId'],['Thông số','key'],['Lý do','message']]);
  }
  async function planDetail(id){const workflow=await api('/api/schedule/plans/'+id+'/workflow'),plan=workflow.plan,readiness=await api('/api/schedule/plans/'+id+'/readiness');const root=panel(plan.name+' · '+text(plan.status)+' · v'+plan.version);
    engineResult(root,plan.result);table(root,plan.roster||[],[['Ca','shiftId'],['Nhân viên',r=>state.employees.find(e=>e.id===r.userId)?.fullName||r.userId]],r=>[plan.status==='draft'&&button('Gỡ',async()=>{await api('/api/schedule/plans/'+id+'/assignments/remove','POST',{...r,expectedVersion:plan.version});await render();await planDetail(id);})]);
    const bar=el('div',null,'toolbar');root.append(bar);
    if(plan.status==='draft'){
      const p=currentPeriod(),shifts=await api(url('/api/schedule/shifts',{periodId:p.id,from:p.startDate,to:p.endDate}));
      bar.append(button('Thêm phân công',()=>edit('Thêm người vào phương án',[{key:'shiftId',label:'Ca',options:shifts.map(s=>[s.id,s.shift.name+' · '+s.shift.date]),required:true},{key:'userId',label:'Nhân viên',options:employees(),required:true}],{},v=>api('/api/schedule/plans/'+id+'/assignments','POST',{...v,expectedVersion:plan.version}))));
      bar.append(button('Xem xét ngoại lệ',()=>edit('Ngoại lệ phân công',[{key:'shiftId',label:'Ca',options:shifts.map(s=>[s.id,s.shift.name+' · '+s.shift.date]),required:true},{key:'userId',label:'Nhân viên',options:employees(),required:true},{key:'reason',label:'Lý do ngoại lệ',type:'textarea',required:true}],{},async v=>{
        const preview=await api('/api/schedule/plans/'+id+'/exceptions/preview','POST',{shiftId:v.shiftId,userId:v.userId});
        if(!preview.allowed)throw new Error(preview.message+'\n'+preview.blocking.map(x=>x.message).join('\n'));
        if(!confirm('Các vi phạm có thể xem xét:\n'+preview.exceptionable.map(x=>x.message).join('\n')+'\nXác nhận ngoại lệ với lý do đã nhập?'))return;
        await api('/api/schedule/plans/'+id+'/exceptions','POST',{...v,expectedVersion:plan.version,previewToken:preview.previewToken,confirm:true});
      },'Xem trước')));
      bar.append(button('Gửi duyệt',async()=>{await api('/api/schedule/plans/'+id+'/submit','POST',{expectedVersion:plan.version});await render();},false));
    }
    if(plan.status==='pending_approval')bar.append(button('Duyệt',async()=>{await api('/api/schedule/plans/'+id+'/approve','POST',{expectedVersion:plan.version});await render();},false),button('Trả về',()=>edit('Trả phương án về nháp',[{key:'reason',label:'Lý do',required:true}],{},v=>api('/api/schedule/plans/'+id+'/reject','POST',{...v,expectedVersion:plan.version}))));
    if(plan.status==='approved')bar.append(button('Công bố',async()=>{if(!confirm('Công bố phương án này? Nhân viên sẽ nhận lịch mới và thông báo.'))return;await api('/api/schedule/plans/'+id+'/publish','POST',{expectedVersion:plan.version,confirmUnderstaffed:confirm('Xác nhận công bố cả khi có ca thiếu người?')});await render();},false));
    root.append(el('p',readiness.canPublish?'Đủ điều kiện công bố.':'Chưa thể công bố. Kiểm tra ràng buộc, độ phủ và ngoại lệ.','muted'));root.scrollIntoView({block:'start'});
  }
  async function calendarScreen(){const root=panel();const controls=el('div',null,'toolbar'),view=field({key:'view',label:'Cách xem',options:[['week','Tuần'],['day','Ngày'],['month','Tháng']]}),date=field({key:'date',label:'Ngày',type:'date'},{date:day()});
    const list=el('div',null,'schedule-list');controls.append(view.label,date.label,button('Xem lịch',load));root.append(controls,list);
    const filters={};if(admin()){const [locations,groups,levels]=await Promise.all([api('/api/schedule/catalog/locations'),api('/api/schedule/catalog/work_groups'),api('/api/levels')]);
      for(const def of [{key:'periodId',label:'Kỳ',options:state.periods.map(p=>[p.id,p.name])},{key:'locationId',label:'Chi nhánh / địa điểm',options:locations.map(x=>[x.id,x.name])},{key:'groupId',label:'Nhóm',options:groups.map(x=>[x.id,x.name])},{key:'userId',label:'Nhân viên',options:employees()},{key:'levelId',label:'Level',options:levels.map(l=>[l.id,l.name])},{key:'status',label:'Trạng thái',options:[['published','Đã công bố'],['draft','Nháp'],['not_scheduled','Chưa xếp']]}]){const f=field(def);filters[def.key]=f.input;controls.append(f.label);}}
    async function load(){const result=await api(url(admin()?'/api/schedule/calendar':'/api/my/schedule',{view:view.input.value,date:date.input.value,...Object.fromEntries(Object.entries(filters).map(([key,input])=>[key,input.value]))}));list.replaceChildren();
      for(const s of result.shifts){const card=el('article');card.append(el('span',s.date,'muted'),el('h3',s.name),el('p',s.startTime+' → '+s.endTime));if(admin()){card.append(el('p',text(s.coverage)+' · '+s.filled+'/'+s.required));for(const person of s.assignees||[])card.append(el('span',person.fullName||person.userId,'chip'));}else card.append(el('p',s.hours+' giờ'));list.append(card);}
      if(!result.shifts.length)list.append(el('p','Chưa có lịch đã công bố trong khoảng này.','empty'));
      if(!admin())for(const version of result.versions||[]){const box=el('article');box.append(el('h3',version.planName||'Phiên bản lịch'),button('Đã xem',async()=>{await api('/api/my/schedule/viewed','POST',{planId:version.planId});notice('Đã ghi nhận xem lịch.');}),button('Xác nhận lịch',async()=>{await api('/api/my/schedule/confirm','POST',{planId:version.planId});await load();notice('Đã xác nhận lịch.');},false));list.append(box);}}
    await load();
  }
  async function requestsScreen(){const root=panel();const rows=await api('/api/requests?scope='+(admin()?'all':'mine'));
    if(!admin())root.append(button('Tạo yêu cầu đổi / thay ca',async()=>{const mine=await api(url('/api/my/schedule',{from:day(),to:new Date(Date.now()+90*86400000).toISOString().slice(0,10)}));
      edit('Chọn ca cần đổi',[{key:'assignmentId',label:'Ca của tôi',options:mine.shifts.map(s=>[s.assignmentId,s.name+' · '+s.date]),required:true}],{},async selected=>{const candidates=await api(url('/api/requests/cover-candidates',{assignmentId:selected.assignmentId}));
        setTimeout(()=>edit('Gửi yêu cầu',[{key:'targetUserId',label:'Đồng nghiệp',options:candidates.map(c=>[c.userId,c.fullName||c.userName||c.userId]),required:true},{key:'type',label:'Hình thức',options:[['cover','Nhờ làm thay'],['swap','Đổi chéo ca']]},{key:'reason',label:'Lý do',required:true}],{},async v=>{if(v.type==='swap'){const options=await api(url('/api/requests/swap-options',{targetUserId:v.targetUserId}));setTimeout(()=>edit('Chọn ca đổi lại',[{key:'targetAssignmentId',label:'Ca của đồng nghiệp',options:options.map(s=>[s.assignmentId,s.shiftName+' · '+s.shiftDate]),required:true}],{},choice=>api('/api/requests','POST',{...v,...choice,sourceAssignmentId:selected.assignmentId})),0);}else await api('/api/requests','POST',{...v,sourceAssignmentId:selected.assignmentId});}),0);
      },'Tiếp tục');},false));
    const decide=(r,action,needNote=false)=>edit(({approve:'Duyệt',reject:'Từ chối',accept:'Đồng ý',decline:'Từ chối',cancel:'Hủy'})[action]+' yêu cầu',[{key:'note',label:'Ghi chú / lý do',required:needNote}],{},v=>api('/api/requests/'+r.id+'/'+action,'POST',{...v,expectedVersion:r.version}));
    table(root,rows,[['Người gửi','requesterName'],['Người nhận','targetUserName'],['Ca',r=>r.sourceShift?.name||r.sourceShift?.shiftName],['Trạng thái','status'],['Lý do','reason']],r=>[
      admin()&&r.status==='pending_approval'&&button('Duyệt',()=>decide(r,'approve')),admin()&&['pending_peer','pending_approval'].includes(r.status)&&button('Từ chối',()=>decide(r,'reject',true)),
      r.targetUserId===state.me.id&&r.status==='pending_peer'&&button('Đồng ý',()=>decide(r,'accept')),r.targetUserId===state.me.id&&r.status==='pending_peer'&&button('Từ chối',()=>decide(r,'decline')),
      r.requesterId===state.me.id&&['pending_peer','pending_approval'].includes(r.status)&&button('Hủy',()=>decide(r,'cancel'))]);
  }
  async function leaveScreen(){const root=panel(),rows=await api('/api/leave?scope='+(admin()?'all':'mine'));
    if(!admin())root.append(button('Gửi đơn nghỉ',async()=>{const types=await api('/api/leave/types');edit('Đơn nghỉ phép',[{key:'leaveTypeId',label:'Loại nghỉ',options:types.map(t=>[t.id,t.name]),required:true},{key:'startDate',label:'Từ ngày',type:'date',required:true},{key:'endDate',label:'Đến ngày',type:'date',required:true},{key:'reason',label:'Lý do',type:'textarea',required:true},{key:'documentUrl',label:'Tài liệu đính kèm',type:'url'}],{},v=>api('/api/leave','POST',v));},false));
    const decision=(r,action)=>edit('Xử lý đơn nghỉ',[{key:'note',label:'Lý do / ghi chú',required:action==='reject'}],{},v=>api('/api/leave/'+r.id+'/'+action,'POST',{...v,expectedVersion:r.version}));
    table(root,rows,[['Nhân viên','userName'],['Loại nghỉ','leaveTypeName'],['Từ ngày','startDate'],['Đến ngày','endDate'],['Trạng thái','status'],['Lý do','reason']],r=>[
      button('Ảnh hưởng lịch',async()=>{const impact=await api('/api/leave/'+r.id+'/impact');table(panel('Ca bị ảnh hưởng'),impact.shifts,[['Ca','shiftName'],['Ngày','date'],['Còn lại','confirmedAfter'],['Nhu cầu','required']]);}),
      admin()&&r.status==='pending'&&button('Duyệt',()=>decision(r,'approve')),admin()&&r.status==='pending'&&button('Từ chối',()=>decision(r,'reject')),r.userId===state.me.id&&r.status==='pending'&&button('Hủy',()=>decision(r,'cancel'))]);
    if(admin())root.append(button('Ghi nhận vắng đột xuất',async()=>{const p=currentPeriod();if(!p)throw new Error('Cần chọn kỳ trước.');const assignments=await api(url('/api/schedule/reports/assignments',{periodId:p.id}));edit('Vắng đột xuất',[{key:'assignmentId',label:'Nhân viên / ca',options:assignments.lines.filter(a=>a.assignmentId&&a.date<=day()).map(a=>[a.assignmentId,a.fullName+' · '+a.shiftName+' · '+a.date]),required:true},{key:'reason',label:'Lý do',required:true}],{},v=>api('/api/leave/absences','POST',v));}));
  }
  async function openShiftsScreen(){const root=panel(),rows=await api('/api/open-shifts');
    if(admin())root.append(button('Mở ca trống',async()=>{const p=currentPeriod();if(!p)throw new Error('Cần tạo kỳ trước.');const shifts=await api(url('/api/schedule/shifts',{periodId:p.id,from:p.startDate,to:p.endDate}));edit('Công bố ca trống',[{key:'shiftId',label:'Ca',options:shifts.filter(s=>s.status==='published').map(s=>[s.id,s.shift.name+' · '+s.shift.date]),required:true},{key:'slots',label:'Số chỗ',type:'number',min:1,required:true},{key:'claimDeadline',label:'Hạn đăng ký',type:'datetime-local',required:true},{key:'note',label:'Ghi chú'}],{},v=>api('/api/open-shifts','POST',v));},false));
    table(root,rows,[['Ca','shiftName'],['Ngày','shiftDate'],['Số chỗ','slots'],['Đã nhận','filledCount'],['Hạn',r=>r.claimDeadlineUtc?new Date(r.claimDeadlineUtc+'Z').toLocaleString('vi-VN'):null],['Trạng thái','status']],r=>[
      !admin()&&r.status==='open'&&button('Đăng ký',async()=>{await api('/api/open-shifts/'+r.id+'/claims','POST',{});await render();}),
      !admin()&&button('Rút đăng ký',async()=>{await api('/api/open-shifts/'+r.id+'/claims/mine','DELETE');await render();}),
      admin()&&button('Đăng ký chờ duyệt',async()=>{const detail=await api('/api/open-shifts/'+r.id);table(panel('Người đăng ký'),detail.claims||[],[['Nhân viên','userName'],['Trạng thái','status']],c=>['pending'].includes(c.status)?[button('Duyệt',async()=>{await api('/api/open-shifts/'+r.id+'/claims/'+c.id+'/approve','POST',{expectedVersion:c.version});await render();}),button('Từ chối',()=>edit('Từ chối đăng ký',[{key:'note',label:'Lý do',required:true}],{},v=>api('/api/open-shifts/'+r.id+'/claims/'+c.id+'/reject','POST',{...v,expectedVersion:c.version})))]:[]);}),
      admin()&&r.status==='open'&&button('Đóng',async()=>{await api('/api/open-shifts/'+r.id+'/close','POST',{expectedVersion:r.version,note:'Đóng đăng ký'});await render();})]);
  }
  async function notificationsScreen(){const root=panel();state.notificationPage=state.notificationPage||0;const page=await api(url('/api/notifications',{page:state.notificationPage,size:20}));
    root.append(button('Đánh dấu tất cả đã đọc',async()=>{await api('/api/notifications/read-all','POST');await render();}));
    table(root,page.items||page.content||page.notifications||[],[['Thông báo','title'],['Nội dung','body'],['Thời điểm','createdAt'],['Đã đọc',n=>n.readAt?'Đã đọc':'Chưa đọc']],n=>[button('Đã đọc',async()=>{await api('/api/notifications/'+n.id+'/read','POST');await render();}),button('Mở',async()=>{await api('/api/notifications/'+n.id+'/read','POST');state.route=({'my-schedule':'calendar','my-requests':'requests',approvals:'requests',leave:'leave','open-shifts':'openShifts',register:'availability'})[n.linkRoute]||'calendar';await render();})]);
    const bar=el('div',null,'toolbar');if(state.notificationPage>0)bar.append(button('Trang trước',async()=>{state.notificationPage--;await render();}));if((page.items||[]).length===20)bar.append(button('Trang sau',async()=>{state.notificationPage++;await render();}));root.append(bar);
    if(admin()){const settings=await api('/api/notifications/settings');root.append(button('Nhắc hạn đăng ký',()=>edit('Cấu hình nhắc hạn',[{key:'hours',label:'Nhắc trước hạn (giờ)',help:'Cách nhau bằng dấu phẩy. Để trống để tắt.'}],{hours:settings.deadlineReminderHours.join(',')},v=>api('/api/notifications/settings','PUT',{deadlineReminderHours:split(v.hours).map(Number)}))));}
  }
  async function reportsScreen(){const root=panel();periodPicker(root);if(!state.periodId)return;
    const summary=await api(url('/api/reports/dashboard',{periodId:state.periodId}));metrics(root,[['Giờ đã xếp',summary.scheduledHours],['Giờ mục tiêu',summary.targetHours],['Chênh lệch',summary.differenceHours],['Tỷ lệ',summary.percentage==null?'Chưa đủ dữ liệu':summary.percentage+'%'],['Giờ thực tế',summary.actualHours],['Giờ đã duyệt',summary.approvedHours],['Chờ xử lý',summary.pendingRequests],['Ca thiếu',summary.understaffedShifts]]);
    root.append(el('p','Lịch đã công bố · Cập nhật '+new Date(summary.generatedAt).toLocaleString('vi-VN'),'muted'));
    if(admin()){
      const versions=await api(url('/api/schedule/reports/versions',{periodId:state.periodId}));const bar=el('div',null,'toolbar'),source=field({key:'source',label:'Nguồn lịch',options:[['','Lịch đã công bố'],...versions.versions.map(v=>[v.planId,v.name+' · '+text(v.workflowStatus)+' · v'+v.version])]}),person=field({key:'userId',label:'Nhân viên',options:employees()});bar.append(source.label,person.label,button('Đối chiếu',loadContext));root.append(bar);
      async function loadContext(){const result=await api(url('/api/schedule/reports/hours',{periodId:state.periodId,source:source.input.value?'plan':'published',planId:source.input.value,userId:person.input.value}));const box=panel(source.input.value?'Giờ theo phương án · v'+result.context.planVersion:'Giờ lịch đã công bố');table(box,result.employees,[['Nhân viên','fullName'],['Đã xếp','hours'],['Mục tiêu','targetHours'],['Trần kỳ','maxHoursPerPeriod'],['Trạng thái','limitStatus']]);}
    }
    const rows=await api(url('/api/reports/users',{periodId:state.periodId}));table(root,rows,[['Nhân viên','name'],['Đã xếp','scheduledHours'],['Mục tiêu','targetHours'],['Thực tế','actualHours'],['Đã duyệt','approvedHours'],['Tỷ lệ (%)','percent'],['Trạng thái','progressLabel']]);
    for(const format of ['csv','xlsx'])root.append(button('Xuất '+format.toUpperCase(),async()=>{const response=await api(url('/api/reports/export',{periodId:state.periodId,format}),'GET',undefined,true),blob=await response.blob(),link=el('a');link.href=URL.createObjectURL(blob);link.download='bao-cao.'+format;link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000);}));
  }
  async function attendanceScreen(){const root=panel();root.append(el('p','Chấm công và duyệt công dùng cùng phiên đăng nhập.'));const frame=el('iframe',null,'frame');frame.title='Chấm công và duyệt công';frame.src='/attendance.html';root.append(frame);}
  async function directoryScreen(){const root=panel();const types=[['locations','Chi nhánh / địa điểm'],['work_groups','Nhóm'],['employee_types','Loại nhân viên'],['positions','Chức vụ'],['skills','Kỹ năng'],['certificates','Chứng chỉ']];
    const data=Object.fromEntries(await Promise.all(types.map(async([type])=>[type,await api('/api/schedule/catalog/'+type)])));const levels=await api('/api/levels');
    function employeeForm(person){edit(person?'Sửa nhân viên':'Thêm nhân viên',[
      {key:'employeeCode',label:'Mã nhân viên',required:true},{key:'username',label:'Tài khoản',required:true},...(!person?[{key:'password',label:'Mật khẩu ban đầu',type:'password',required:true,help:'Ít nhất 8 ký tự.'}]:[]),{key:'fullName',label:'Họ tên',required:true},
      {key:'levelId',label:'Level',options:levels.map(l=>[l.id,l.name])},{key:'status',label:'Trạng thái',options:[['active','Đang làm việc'],['inactive','Ngừng hoạt động']],value:'active'},
      ...[['branchId','Chi nhánh','locations'],['groupId','Nhóm','work_groups'],['employeeTypeId','Loại nhân viên','employee_types']].map(([key,label,type])=>({key,label,options:data[type].map(x=>[x.id,x.name])}))
    ],person||{},v=>api('/api/workspace/employees'+(person?'/'+person.id:''),person?'PUT':'POST',v));}
    root.append(button('Thêm nhân viên',()=>employeeForm(),false));table(root,state.employees,[['Mã','employeeCode'],['Họ tên','fullName'],['Tài khoản','username'],['Trạng thái','status']],p=>[
      button('Sửa',()=>employeeForm(p)),button('Năng lực / địa điểm',async()=>{const links=await api('/api/schedule/catalog/users/'+p.id);const values={...links,certificateIds:links.certificates.map(c=>c.certificateId)};for(const c of links.certificates)values['valid_'+c.certificateId]=c.validUntil;
        const defs=[...[['locationIds','Địa điểm','locations'],['positionIds','Chức vụ','positions'],['skillIds','Kỹ năng','skills'],['certificateIds','Chứng chỉ','certificates']].map(([key,label,type])=>({key,label,options:data[type].map(x=>[x.id,x.name]),multiple:true})),...data.certificates.map(c=>({key:'valid_'+c.id,label:'Hết hạn: '+c.name,type:'date',help:'Để trống nếu không hết hạn.'}))];
        edit('Năng lực: '+p.fullName,defs,values,v=>api('/api/schedule/catalog/users/'+p.id,'PUT',{locationIds:v.locationIds,positionIds:v.positionIds,skillIds:v.skillIds,certificates:v.certificateIds.map(id=>({certificateId:id,validUntil:v['valid_'+id]}))}));})]);
    const catalogs=panel('Danh mục');const chooser=field({key:'type',label:'Danh mục',options:types},{type:state.catalogType||'locations'});catalogs.append(chooser.label);chooser.input.onchange=async()=>{state.catalogType=chooser.input.value;await render();};const type=chooser.input.value;
    catalogs.append(button('Thêm vào danh mục',()=>edit('Thêm '+types.find(x=>x[0]===type)[1],[{key:'name',label:'Tên',required:true}],{},v=>api('/api/schedule/catalog/'+type,'POST',v))));table(catalogs,data[type],[['Tên','name']],r=>[button('Xóa',async()=>{if(confirm('Xóa mục chưa được sử dụng này?')){await api('/api/schedule/catalog/'+type+'/'+r.id,'DELETE');await render();}})]);
  }
  const routes={periods:['Kỳ làm việc',periodsScreen],availability:['Đăng ký thời gian',availabilityScreen],rules:['Quy tắc',rulesScreen],shifts:['Ca làm',shiftsScreen],plans:['Phương án lịch',plansScreen],calendar:['Lịch làm việc',calendarScreen],requests:['Đổi / thay ca',requestsScreen],leave:['Nghỉ phép',leaveScreen],openShifts:['Ca trống',openShiftsScreen],notifications:['Thông báo',notificationsScreen],attendance:['Chấm công',attendanceScreen],reports:['Báo cáo',reportsScreen],directory:['Nhân viên & danh mục',directoryScreen]};
  function navigation(){const nav=$('navigation');nav.replaceChildren();for(const [id,[title]]of Object.entries(routes)){if(!admin()&&['shifts','plans','directory'].includes(id))continue;const b=button(title+(id==='notifications'&&state.unread?' ('+state.unread+')':''),async()=>{state.route=id;await render();});b.dataset.route=id;if(id===state.route)b.setAttribute('aria-current','page');nav.append(b);}}
  async function render(){if(!state.me)return;const seq=++renderSequence;navigation();$('page-title').textContent=routes[state.route][0];$('content').replaceChildren(el('p','Đang tải…','muted'));$('content').setAttribute('aria-busy','true');notice('');
    try{const periods=await api('/api/periods');state.unread=(await api('/api/notifications/unread-count')).unread;if(admin())state.employees=await api('/api/workspace/employees');if(seq!==renderSequence)return;navigation();state.periods=periods;if(!periods.some(p=>p.id===state.periodId))state.periodId=periods[0]?.id||'';$('content').replaceChildren();await routes[state.route][1]();}
    catch(e){notice(e.message,true);}finally{if(seq===renderSequence)$('content').setAttribute('aria-busy','false');}
  }
  async function boot(){state.me=await api('/api/attendance/me');if(admin())state.employees=await api('/api/workspace/employees');$('identity').textContent=state.me.full_name||state.me.fullName||'';$('login').hidden=true;$('workspace').hidden=false;state.route=admin()?'periods':'calendar';await render();}
  function logout(){state.token='';state.me=null;state.employees=[];state.periods=[];state.periodId='';state.planId='';state.rulePreview=null;state.unread=0;state.notificationPage=0;renderSequence++;sessionStorage.removeItem('attendance_jwt');$('content').replaceChildren();$('workspace').hidden=true;$('login').hidden=false;if($('editor').open)$('editor').close();}
  $('logout').onclick=async()=>{try{await api('/api/auth/logout','POST');}finally{logout();}};
  $('refresh').onclick=render;
  $('login-form').onsubmit=async e=>{e.preventDefault();const b=e.target.querySelector('button');b.disabled=true;$('login-error').textContent='';try{const form=new FormData(e.target),result=await api('/api/auth/login','POST',{username:form.get('username'),password:form.get('password')});state.token=result.token;sessionStorage.setItem('attendance_jwt',state.token);e.target.reset();await boot();}catch(error){$('login-error').textContent=error.message;}finally{b.disabled=false;}};
  if(state.token)boot().catch(error=>{logout();$('login-error').textContent=error.message;});
})();
