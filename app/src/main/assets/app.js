let key=localStorage.getItem('watchgate_key')||sessionStorage.getItem('watchgate_key')||'';
const $=id=>document.getElementById(id);
let toastTimer, editing=false, savedMinutes=30, appCatalog=[], activeList='allow', curriculumBooks=[], bankGenerating=false;
let questionData=null, questionFilter='all', questionSeq=0, applyingBank=false, deletingBank=false, questionQueryKey='';
let questionSource=localStorage.getItem('watchgate_question_source')||'chapter';
if(!['image','document','chapter'].includes(questionSource))questionSource='chapter';
const sourceNames={image:'图片',document:'文档',chapter:'章节'};
let examReportGrade=0, examReportFingerprint='', examUploading=false, examRunning=false, examLocalError='';
let documentRunning=false, documentUploading=false;
let reviewQueue=[],activeReviewId='',reviewLoading=false,reviewSubmitting=false,reviewAutoPaused=false,reviewFingerprint='';
let scheduleTasks=[];
let practiceState=null,practicePosting=false;
let generationSnapshot=null, latestJob='', latestJobStatus='';
let autoCompletionRevision=null, autoCompletionVisible=false, autoCompletionTimer;
let familyShareToken='', iconUpdating=false, roundQuestionsSaving=false;
let refreshInFlight=false, refreshFailures=0, stateRenderVersion=0;
let generationPolling=false, generationTimer=null;
for(let count=1;count<=20;count++) {
  $('roundQuestions').add(new Option(String(count),String(count)));
  $('scheduleQuestions').add(new Option(String(count),String(count)));
}
const difficultyNames={basic:'基础',standard:'适中',advanced:'提高'};
const gradeNames={1:'小学一年级',2:'小学二年级',3:'小学三年级',4:'小学四年级',5:'小学五年级',6:'小学六年级',7:'初中一年级',8:'初中二年级',9:'初中三年级',10:'高中一年级',11:'高中二年级',12:'高中三年级'};

function toast(message){
  const el=$('toast'); el.textContent=message; el.classList.add('show');
  clearTimeout(toastTimer); toastTimer=setTimeout(()=>el.classList.remove('show'),2800);
}
async function api(path,body){
  // 请求超时后释放轮询，避免一次断线让后续进度刷新永久停止。
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),body?45000:12000);
  const options={headers:{'X-Parent-Key':key},cache:'no-store',signal:controller.signal};
  if(body){options.method='POST';options.headers['Content-Type']='application/json';options.body=JSON.stringify(body)}
  try{
  const response=await fetch(path,options);
  if(response.status===401){
    key='';localStorage.removeItem('watchgate_key');sessionStorage.removeItem('watchgate_key');
    showLogin();throw Error('请重新连接手机');
  }
  if(!response.ok){let error;try{error=(await response.json()).error}catch(e){}throw Error(error||'操作失败')}
  return await response.json();
  }catch(error){if(error.name==='AbortError')throw Error('请求超时，请检查设备连接');throw error}
  finally{clearTimeout(timer)}
}
function renderFamilyShare(token){
  familyShareToken=token||'';
  const active=!!familyShareToken;
  $('familyShareLabel').textContent=active?'分享1':'分享';
  $('familyShareCreate').hidden=active;
  $('familyShareCopy').hidden=!active;
  $('familyShareRevoke').hidden=!active;
  $('familyShareStatus').textContent=active?'家人链接已生成':'尚未生成家人链接';
}
async function loadFamilyShare(){
  if(!key)return;
  try{const data=await api('/api/family-link');renderFamilyShare(data.token||'')}
  catch(error){$('familyShareStatus').textContent='暂时无法读取分享链接'}
}
async function copyFamilyShare(){
  if(!familyShareToken)return;
  const url=location.origin+'/family?token='+encodeURIComponent(familyShareToken);
  let copied=false;
  try{if(navigator.clipboard&&navigator.clipboard.writeText){await navigator.clipboard.writeText(url);copied=true}}
  catch(error){}
  if(!copied){
    const input=document.createElement('textarea');input.value=url;input.setAttribute('readonly','');
    input.style.position='fixed';input.style.opacity='0';document.body.append(input);input.select();
    try{copied=document.execCommand('copy')}catch(error){}
    input.remove();
  }
  if(copied)toast('分享链接已复制');else window.prompt('复制分享链接',url);
}
function showLogin(){
  stateRenderVersion++;refreshFailures=0;
  if($('importReviewDialog').open)$('importReviewDialog').close();
  activeReviewId='';reviewQueue=[];reviewFingerprint='';
  $('login').hidden=false;$('dashboard').hidden=true;$('launcherIconControl').hidden=true;
  $('connection').textContent='等待连接';$('connection').className='badge';
}
function duration(ms){
  const seconds=Math.ceil(Math.max(0,ms)/1000);
  return String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');
}
function readable(ms){
  const minutes=Math.floor(ms/60000),seconds=Math.floor(ms/1000)%60;
  return minutes>=60?Math.floor(minutes/60)+' 小时 '+minutes%60+' 分钟':minutes>0?minutes+' 分钟 '+seconds+' 秒':seconds+' 秒';
}
function syncLimitLabel(){const n=Number($('minutes').value);$('limitValue').textContent=n>60?Math.floor(n/60)+' 小时'+(n%60?' '+n%60+' 分钟':''):n+' 分钟'}
function syncGenerateButton(){
  const count=$('examImages').files.length, autoBusy=!!(generationSnapshot&&generationSnapshot.autoSupplyRunning);
  const busy=bankGenerating||autoBusy||examRunning||examUploading||documentRunning||documentUploading;
  if(busy&&!generationPolling)scheduleGeneration(2000);
  $('generateBank').disabled=busy||(!count&&!$('bankChapter').value);
  // 仅在任务进行时把真实已保存数量放到按钮内，结束后恢复按钮文字。
  const current=generationSnapshot?generationSnapshot.progressCurrent:0,target=generationSnapshot?generationSnapshot.progressTarget:10;
  $('generateBank').textContent=bankGenerating?'生成中 '+current+'/'+(target||10)+' 道':autoBusy?'正在提前准备题目…':examRunning||examUploading?'正在处理图片…':documentRunning||documentUploading?'正在处理文档…':'生成';
  const progress=$('generationProgress');
  progress.hidden=!bankGenerating;
  if(bankGenerating)progress.textContent=(generationSnapshot&&generationSnapshot.generating?generationSnapshot.status:latestJobStatus)+' · 已用时 '+readable(generationSnapshot&&generationSnapshot.generating?generationSnapshot.elapsedMs||0:0);
  const autoStatus=$('autoSupplyStatus'),autoMessage=generationSnapshot&&generationSnapshot.autoSupplyStatus||'';
  const completed=!autoBusy&&/^(已准备好|已提前准备)/.test(autoMessage);
  if(completed){
    // 用补题版本区分新完成的任务，避免后台例行检查反复显示同一提示。
    const revision=generationSnapshot.autoSupplyRevision||0;
    if(autoCompletionRevision!==revision){
      autoCompletionRevision=revision;autoCompletionVisible=true;clearTimeout(autoCompletionTimer);
      autoCompletionTimer=setTimeout(()=>{autoCompletionVisible=false;syncGenerateButton()},5000);
    }
  }
  autoStatus.hidden=!autoMessage||(completed&&!autoCompletionVisible);
  autoStatus.textContent=autoMessage;
  $('clearExamImages').hidden=count===0&&!$('documentFile').files.length;
  const error=$('generationError');
  error.hidden=!latestJob||!/(失败|中断|未开始)/.test(latestJobStatus);
  error.textContent=error.hidden?'':latestJobStatus;
}
function renderPracticeControls(){
  const state=practiceState||{},answered=state.answered||0;
  $('startPractice').disabled=practicePosting||!!state.enabled;
  $('startPractice').textContent=state.enabled?'刷题中（'+answered+'/50）':'开始刷题（50题）';
}
function render(state){
  stateRenderVersion++;refreshFailures=0;
  practiceState=state.practice;renderPracticeControls();
  $('login').hidden=true;$('dashboard').hidden=false;$('launcherIconControl').hidden=false;
  $('connection').textContent='手机已连接';$('connection').className='badge ok';
  $('remaining').textContent=duration(state.remainingMs);
  $('mode').textContent=state.scheduledPause?'定时暂停中':state.quizTest?'答题锁定测试 · 保留本轮时间':state.scheduledHold?'定时答题待完成':state.scheduledWindow?'定时弹题计时中':'';
  $('mode').hidden=!state.scheduledPause&&!state.quizTest&&!state.scheduledHold&&!state.scheduledWindow;
  $('controlToggle').checked=!!state.enabled;
  if(!iconUpdating)$('launcherIconToggle').checked=!state.launcherIconVisible;
  if(!roundQuestionsSaving)$('roundQuestions').value=String(state.roundQuestions||1);
  $('health').textContent=(!state.usagePermission?'需要开启使用情况访问；':'')+(!state.overlayPermission?'需要开启悬浮窗权限；':'')+(state.error||'');
  $('diagnostics').textContent='最近一次后台状态：\n'+(state.lastBackground||'暂无记录')+(state.error?'\n\n运行提示：'+state.error:'');
  savedMinutes=Math.max(1,Math.round(state.limitMs/60000));
  if(!editing){$('minutes').value=savedMinutes;syncLimitLabel()}
}
async function refresh(){
  if(!key||refreshInFlight||document.hidden)return;
  refreshInFlight=true;
  const renderVersion=stateRenderVersion;
  try{
    const state=await api('/api/state');
    // 不让较早的轮询结果覆盖后来成功的家长操作。
    if(renderVersion===stateRenderVersion&&key)render(state);
  }catch(error){
    if(!key){toast(error.message);return}
    if(renderVersion===stateRenderVersion){
      refreshFailures++;
      $('connection').textContent=refreshFailures<3?'正在重连':'连接中断';
      $('connection').className='badge';
    }
  }finally{refreshInFlight=false}
}
async function usage(){
  try{
    const data=await api('/api/usage'),container=$('usage');container.replaceChildren();
    if(!data.permission){container.textContent='请在儿童手机开启使用情况访问。';return}
    if(!data.apps.length){container.textContent='今天暂无使用记录。';return}
    for(const app of data.apps){
      const row=document.createElement('div');row.className='usage-row';
      const name=document.createElement('span');name.textContent=app.name;
      const time=document.createElement('span');time.textContent=readable(app.ms);
      row.append(name,time);container.append(row);
    }
  }catch(error){toast(error.message)}
}
async function questionBank(){
  if(!key)return;
  try{
    const data=await api('/api/quizbank');
    generationSnapshot=data;
    if(data.generating) latestJob='generate';
    else if(!latestJob && data.lastJob) {
      latestJob=data.lastJob;
      if(latestJob==='exam')examState(); else if(latestJob==='document')documentState();
    } else if(!latestJob && data.progressTarget) latestJob='generate';
    if(latestJob==='generate')latestJobStatus=data.status;
    bankGenerating=data.generating;
    syncGenerateButton();
  }catch(error){if(latestJob==='generate'){latestJobStatus='连接中断，暂时无法读取生成进度';syncGenerateButton()}}
}
function renderChapters(){
  // 年级和册别只显示已经内置的教材，后续增加教材时自动扩展。
  const gradeSelect=$('bankGrade'),subjectSelect=$('bankSubject'),termSelect=$('bankTerm'),select=$('bankChapter');
  const grades=[...new Set(curriculumBooks.map(book=>book.grade))].sort((a,b)=>a-b);
  const oldGrade=Number(localStorage.getItem('watchgate_bank_grade')||gradeSelect.value);gradeSelect.replaceChildren();
  for(const value of grades)gradeSelect.add(new Option(gradeNames[value]||String(value),String(value)));
  if(grades.includes(oldGrade))gradeSelect.value=String(oldGrade);
  const grade=Number(gradeSelect.value),gradeBooks=curriculumBooks.filter(book=>book.grade===grade);
  const subjects=[...new Set(gradeBooks.map(book=>book.subject))].sort((a,b)=>['语文','数学','英语'].indexOf(a)-['语文','数学','英语'].indexOf(b));
  const oldSubject=localStorage.getItem('watchgate_bank_subject')||'数学';subjectSelect.replaceChildren();
  for(const subject of subjects)subjectSelect.add(new Option(subject,subject));
  if(subjects.includes(oldSubject))subjectSelect.value=oldSubject;
  const subject=subjectSelect.value,terms=[...new Set(gradeBooks.filter(book=>book.subject===subject).map(book=>book.term))];
  const oldTerm=localStorage.getItem('watchgate_bank_term')||termSelect.value;termSelect.replaceChildren();
  for(const value of terms)termSelect.add(new Option(value+'册',value));
  if(terms.includes(oldTerm))termSelect.value=oldTerm;
  const term=termSelect.value;
  if(grade)localStorage.setItem('watchgate_bank_grade',String(grade));
  if(subject)localStorage.setItem('watchgate_bank_subject',subject);
  if(term)localStorage.setItem('watchgate_bank_term',term);
  const book=curriculumBooks.find(item=>item.grade===grade&&item.subject===subject&&item.term===term);
  const previous=localStorage.getItem('watchgate_bank_chapter')||'';
  select.replaceChildren();
  if(!book||!book.chapters.length){select.add(new Option('暂无此年级章节',''));}
  else for(const chapter of book.chapters){select.add(new Option(chapter.title+(chapter.status==='published'?'':'（审校中）'),chapter.id));}
  if([...select.options].some(option=>option.value===previous))select.value=previous;
  syncTextbookLabel();
  syncGenerateButton();
  loadQuestions();
}
function syncTextbookLabel(){
  const grade=Number($('bankGrade').value),subject=$('bankSubject').value,term=$('bankTerm').value;
  const book=curriculumBooks.find(item=>item.grade===grade&&item.subject===subject&&item.term===term);
  const chapter=book&&book.chapters.find(item=>item.id===$('bankChapter').value);
  $('bankTextbook').hidden=!book;
  $('bankTextbook').textContent=book?'教材：'+(chapter&&chapter.publisher||book.publisher||book.title)+' · '+(gradeNames[grade]||'')+subject+term+'册':'';
}
async function loadCurriculum(){
  if(!key)return;
  try{curriculumBooks=await api('/api/curriculum');renderChapters();if(activeReviewId){const issue=reviewQueue.find(item=>item.id===activeReviewId);fillReviewChapters($('reviewChapter').value||(issue?issue.chapterId:''))}}
  catch(error){$('bankChapter').replaceChildren(new Option('章节暂不可用',''));syncGenerateButton();toast(error.message)}
}
async function loadQuestions(){
  if(!key)return;
  const grade=Number($('bankGrade').value),term=$('bankTerm').value,source=questionSource;
  const chapterId=source==='chapter'?$('bankChapter').value:'__all__';
  const seq=++questionSeq;
  const queryKey=source==='chapter'?[grade,term,chapterId,source].join('|'):source;
  if(queryKey!==questionQueryKey){questionQueryKey=queryKey;questionData=null;$('questionRows').replaceChildren();$('applyQuestionBank').disabled=true;$('deleteQuestionBank').disabled=true;}
  if(!chapterId){questionData=null;$('questionSummary').textContent='请选择教材章节';$('questionRows').replaceChildren();$('deleteQuestionBank').disabled=true;return}
  try{
    const data=await api('/api/question-list',{grade,term,chapterId,sourceType:source});if(seq!==questionSeq)return;
    questionData=data;
    let text=(source==='chapter'?$('bankChapter').selectedOptions[0].text+' · ':'')+sourceNames[source]+'题库 · '+
      '一共 '+data.total+' 题 · 基础题 '+data.baseCount+' 题 · 答对 '+data.correct+' · 答错 '+data.wrong+' · 超时 '+data.timeout;
    $('questionSummary').textContent=text;renderQuestionRows();
  }catch(error){if(seq===questionSeq){$('questionSummary').textContent='暂时无法读取题目记录';$('deleteQuestionBank').disabled=true}}
}
// 显示与应用共用同一筛选结果，避免应用到列表外的题目。
function visibleQuestions(){
  if(!questionData)return [];
  const matches=row=>questionFilter==='all'||(questionFilter==='unseen'&&row.historyKnown&&row.shown===0)||
    (questionFilter==='correct'&&row.correct>0)||(questionFilter==='wrong'&&row.wrong>0)||
    (questionFilter==='timeout'&&row.timeout>0);
  return questionData.rows.filter(matches);
}
function renderQuestionRows(){
  const body=$('questionRows');body.replaceChildren();if(!questionData)return;
  const rows=visibleQuestions();
  $('applyQuestionBank').disabled=applyingBank||deletingBank||!rows.length;
  $('deleteQuestionBank').disabled=applyingBank||deletingBank||!questionData.rows.length;
  if(!rows.length){const tr=document.createElement('tr'),td=document.createElement('td');td.colSpan=3;td.textContent='暂无符合条件的题目';tr.append(td);body.append(tr);return}
  for(const row of rows){
    const tr=document.createElement('tr'),question=document.createElement('td'),type=document.createElement('td'),status=document.createElement('td');
    const details=document.createElement('details'),summary=document.createElement('summary'),info=document.createElement('p');
    summary.textContent=row.stem;info.className='detail';
    info.textContent='知识点：'+row.topic+'\n'+row.choices.map((choice,i)=>String.fromCharCode(65+i)+'. '+choice).join('\n')+'\n答案：'+row.answer+(row.nextReview?'\n下次复习：'+new Date(row.nextReview).toLocaleString('zh-CN'):'');
    details.append(summary,info);question.append(details);
    type.textContent=row.kind==='auto_practice'?'自动补充题':row.kind==='variant'?'变体':row.kind==='exam'?'试卷专项':row.kind==='extension'?'拓展原题':row.kind==='practice'?'刷题补充题':'基础原题';
    const tags=[];if(row.historyKnown&&!row.shown)tags.push('未出现');
    if(row.shown)tags.push('出现 '+row.shown+' 次');if(row.correct)tags.push('答对 '+row.correct+' 次');if(row.wrong)tags.push('答错 '+row.wrong+' 次');if(row.timeout)tags.push('超时 '+row.timeout+' 次');
    status.textContent=tags.join(' · ')||'等待记录';tr.append(question,type,status);body.append(tr);
  }
}

async function examState(){
  if(!key)return;
  try{
    const data=await api('/api/exam');examRunning=!!data.running;
    if(data.running)latestJob='exam';if(latestJob==='exam')latestJobStatus=data.status||'正在处理图片';syncGenerateButton();
    if(!examUploading){$('examStatus').textContent=examLocalError||data.status;$('examStatus').hidden=!examLocalError&&!data.running&&['尚未分析试卷','已保存上次分析'].includes(data.status)}
    $('retryExam').setAttribute('aria-disabled',String(examRunning||bankGenerating||examUploading));
    const report=data.report;if(!report){$('examReport').hidden=true;return}
    examReportGrade=report.grade;const fingerprint=JSON.stringify(report);
    if(fingerprint===examReportFingerprint)return;examReportFingerprint=fingerprint;
    $('examReport').hidden=false;
    const kind=report.sourceKind==='answered_exam'?'已作答试卷':report.sourceKind==='questions'?'纯题目':'类型待确认';
    $('examSummary').textContent=gradeNames[report.grade]+' · '+kind+' · 识别 '+report.questions.length+' 题 · 已导入 '+(report.importCount||0)+' 道'+(report.sourceKind==='questions'?'':' · 专项题 '+report.practiceCount+' 道')+'。'+(report.summary||'');
    const pure=report.sourceKind==='questions',weaknesses=report.weaknesses||[];
    $('examReport').querySelector('summary').textContent=pure?'查看题目识别结果':'查看试卷分析结果';
    $('examWeaknesses').hidden=pure||report.sourceKind!=='answered_exam'||!weaknesses.length;
    $('examWeaknesses').textContent=weaknesses.length?'薄弱点：'+weaknesses.map(group=>group.subject+'：'+group.points.join('、')).join('；'):'';
    $('retryExam').hidden=pure||!weaknesses.some(group=>!group.done);
    const rows=$('examRows');rows.replaceChildren();
    const labels={correct:'答对',wrong:'答错',unanswered:'未作答',uncertain:'待确认'};
    const evidence={teacher_mark:'教师批改及 AI 判断',ai_judgment:'AI 判断',unclear:'依据不清'};
    for(const item of report.questions){
      const row=document.createElement('div'),heading=document.createElement('strong'),detail=document.createElement('p');row.className='exam-row';
      heading.textContent='第 '+item.number+' 题 · '+(labels[item.status]||'待确认')+(item.subject?' · '+item.subject:'');
      const lines=[item.stem,item.studentAnswer?'孩子作答：'+item.studentAnswer:'',item.status!=='uncertain'&&item.correctAnswer?'参考答案：'+item.correctAnswer:'',item.knowledgePoint?'知识点：'+item.knowledgePoint:'',item.reason?'判断说明：'+item.reason:'','依据：'+(evidence[item.basis]||'不清')];
      detail.textContent=lines.filter(Boolean).join('\n');row.append(heading,detail);rows.append(row);
    }
  }catch(error){if(!examUploading){$('examStatus').textContent='暂时无法读取图片处理状态';$('examStatus').hidden=false;if(latestJob==='exam'){latestJobStatus='连接中断，暂时无法读取图片进度';syncGenerateButton()}}}
}
function readImage(file){
  return new Promise((resolve,reject)=>{
    const reader=new FileReader();reader.onerror=()=>reject(Error('读取图片失败'));reader.onload=()=>{
      const img=new Image();img.onerror=()=>reject(Error('图片无法打开，请换一张'));img.onload=()=>{
        const scale=Math.min(1,3000/Math.max(img.width,img.height)),canvas=document.createElement('canvas');
        canvas.width=Math.max(1,Math.round(img.width*scale));canvas.height=Math.max(1,Math.round(img.height*scale));
        const ctx=canvas.getContext('2d');if(!ctx){reject(Error('无法处理图片'));return}
        ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.drawImage(img,0,0,canvas.width,canvas.height);
        resolve(canvas.toDataURL('image/jpeg',0.9));
      };img.src=reader.result;
    };reader.readAsDataURL(file);
  });
}
function renderAppRules(){
  const white=appCatalog.filter(app=>app.policy==='allow');
  const black=appCatalog.filter(app=>app.policy==='block');
  $('whiteCount').textContent=white.length;$('blackCount').textContent=black.length;
  const current=activeList==='allow'?white:black;
  $('confirmAddApp').textContent=activeList==='allow'?'加入白名单':'加入黑名单';
  document.querySelectorAll('[data-list]').forEach(button=>{
    button.classList.toggle('selected',button.dataset.list===activeList);
    button.setAttribute('aria-expanded',String(button.dataset.list===activeList&&!$('addAppPanel').hidden));
  });
  const picker=$('appPicker'),selected=picker.value;
  picker.replaceChildren();
  const first=document.createElement('option');first.value='';first.textContent='选择应用';picker.append(first);
  for(const app of appCatalog){
    const option=document.createElement('option');option.value=app.package;
    option.textContent=app.label+(app.policy==='allow'?'（已在白名单）':app.policy==='block'?'（已在黑名单）':'');
    option.disabled=app.policy===activeList;
    picker.append(option);
  }
  if(appCatalog.some(app=>app.package===selected))picker.value=selected;
  const container=$('activeApps');container.replaceChildren();
  if(!current.length){const empty=document.createElement('p');empty.className='empty-list';empty.textContent='这个名单还没有应用';container.append(empty)}
  for(const app of current){
    const row=document.createElement('div');row.className='app-row';
    const name=document.createElement('span');name.className='app-name';name.textContent=app.label;
    const remove=document.createElement('button');remove.type='button';remove.className='remove-app';
    remove.textContent='移除';remove.setAttribute('aria-label','从'+(activeList==='allow'?'白名单':'黑名单')+'移除'+app.label);
    remove.addEventListener('click',()=>saveAppPolicy(app.package,'none'));
    row.append(name,remove);container.append(row);
  }
  $('appRulesStatus').textContent=activeList==='allow'?'白名单应用不计时。':'黑名单应用打开后进入 50 题挑战。';
}
async function loadAppRules(){
  if(!key)return;
  try{const data=await api('/api/apps');appCatalog=data.apps;renderAppRules()}
  catch(error){$('appRulesStatus').textContent='无法读取应用列表；请检查手机连接和使用情况访问权限'}
}
async function saveAppPolicy(packageName,policy){
  try{
    const rows=appCatalog.map(app=>({package:app.package,policy:app.package===packageName?policy:app.policy}));
    const data=await api('/api/app-rules',{apps:rows});appCatalog=data.apps;renderAppRules();
    toast(policy==='none'?'已从名单移除':policy==='allow'?'已加入白名单':'已加入黑名单');
  }catch(error){toast(error.message);loadAppRules()}
}
async function addAppToList(){
  const packageName=$('appPicker').value;
  if(!packageName){toast('请先选择应用');return}
  const selected=appCatalog.find(app=>app.package===packageName);
  if(selected && selected.policy===activeList){toast('该应用已在当前名单');return}
  await saveAppPolicy(packageName,activeList);
  $('appPicker').value='';$('addAppPanel').hidden=true;renderAppRules();
}
async function pair(code){
  const response=await fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code}),cache:'no-store'});
  const result=await response.json();if(!response.ok)throw Error(result.error||'配对失败');
  key=result.key;localStorage.setItem('watchgate_key',key);sessionStorage.removeItem('watchgate_key');
  $('key').value='';render(await api('/api/state'));usage();loadAppRules();loadCurriculum();loadSchedules();loadFamilyShare();syncGeneration();
}

$('loginForm').addEventListener('submit',async event=>{
  event.preventDefault();try{await pair($('key').value.trim())}catch(error){toast(error.message)}
});
async function loadSchedules(){
  if(!key)return;
  try{const data=await api('/api/schedules');scheduleTasks=data.tasks||[];renderSchedules()}
  catch(error){$('scheduleList').textContent='无法读取定时任务：'+error.message}
}
function renderSchedules(){
  const list=$('scheduleList');list.replaceChildren();
  if(!scheduleTasks.length){list.textContent='暂无定时任务';return}
  const dayNames={1:'日',2:'一',3:'二',4:'三',5:'四',6:'五',7:'六'};
  for(const task of scheduleTasks){
    const row=document.createElement('div'),label=document.createElement('span'),remove=document.createElement('button');row.className='schedule-item';
    const days=task.days.length===7?'每天':'周'+task.days.map(day=>dayNames[day]).join('、');
    label.textContent=days+' · '+(task.type==='pause'?'暂停 '+task.time+' → '+(task.end<task.time?'次日 ':'')+task.end:task.type==='quiz-window'?'弹题 '+task.time+' → '+(task.end<task.time?'次日 ':'')+task.end+' · 每轮 '+task.questions+' 题':task.type==='practice'?task.time+' 刷题（50题）':task.time+' 弹题');
    remove.type='button';remove.textContent='删除';remove.addEventListener('click',async()=>{
      if(!confirm('删除这条定时任务？'))return;
      try{const data=await api('/api/schedules',{tasks:scheduleTasks.filter(item=>item.id!==task.id)});scheduleTasks=data.tasks;renderSchedules();toast('已删除')}
      catch(error){toast(error.message)}
    });row.append(label,remove);list.append(row);
  }
}
$('scheduleType').addEventListener('change',()=>{
  const type=$('scheduleType').value,pause=type==='pause';
  $('scheduleEndLabel').hidden=type==='practice';
  $('scheduleEndText').textContent=pause?'恢复时间':'结束时间';
  $('scheduleQuestionsLabel').hidden=type!=='quiz-window';
});
$('addSchedule').addEventListener('click',async()=>{
  const type=$('scheduleType').value,time=$('scheduleTime').value,end=$('scheduleEnd').value;
  const days=[...document.querySelectorAll('[name=scheduleDay]:checked')].map(input=>Number(input.value));
  if(!time || !days.length || (type!=='practice'&&(!end||end===time))){toast('请选择时间和重复日期，开始与结束时间不能相同');return}
  const id='t'+Date.now().toString(36)+Math.random().toString(36).slice(2,10);
  try{const data=await api('/api/schedules',{tasks:[...scheduleTasks,{id,type,time,end,questions:Number($('scheduleQuestions').value),days,enabled:true}]});scheduleTasks=data.tasks;renderSchedules();toast('定时任务已保存')}
  catch(error){toast(error.message)}
});
$('minutes').addEventListener('input',()=>{editing=true;syncLimitLabel()});
$('minutes').addEventListener('change',async()=>{
  const minutes=Number($('minutes').value);
  if(!confirm('保存后会从新一轮开始计时，继续吗？')){
    editing=false;$('minutes').value=savedMinutes;syncLimitLabel();return;
  }
  try{editing=false;render(await api('/api/action',{action:'limit',minutes}));toast('时长已保存')}
  catch(error){editing=false;$('minutes').value=savedMinutes;syncLimitLabel();toast(error.message)}
});
$('controlToggle').addEventListener('change',async()=>{
  const enabled=$('controlToggle').checked;
  if(!enabled&&!confirm('暂停整机管控？儿童手机将不再按时长弹题。')){$('controlToggle').checked=true;return}
  try{render(await api('/api/action',{action:enabled?'start':'pause'}));toast(enabled?'管控已开启':'管控已暂停')}
  catch(error){$('controlToggle').checked=!enabled;toast(error.message)}
});
$('roundQuestions').addEventListener('change',async()=>{
  const select=$('roundQuestions'),count=Number(select.value);
  roundQuestionsSaving=true;select.disabled=true;
  try{const state=await api('/api/action',{action:'question-count',count});roundQuestionsSaving=false;render(state);toast('每轮题数已保存')}
  catch(error){toast(error.message)}
  finally{roundQuestionsSaving=false;select.disabled=false;refresh()}
});
$('launcherIconToggle').addEventListener('change',async()=>{
  const toggle=$('launcherIconToggle'),visible=!toggle.checked;
  iconUpdating=true;toggle.disabled=true;
  try{const state=await api('/api/launcher-icon',{visible});iconUpdating=false;render(state);toast(visible?'桌面图标已恢复':'桌面图标已隐藏')}
  catch(error){toggle.checked=visible;toast(error.message)}
  finally{iconUpdating=false;toggle.disabled=false;refresh()}
});
document.querySelectorAll('[data-action]').forEach(button=>button.addEventListener('click',async()=>{
  const action=button.dataset.action;
  if(action==='lock'&&!confirm('立即在儿童手机显示答题页？'))return;
  if(action==='release'&&!confirm(practiceState&&practiceState.enabled?'家长放行并结束刷题？进度会保留。':'家长放行当前答题？'))return;
  try{render(await api('/api/action',{action}));toast('已更新')}
  catch(error){toast(error.message)}
}));
// 刷题的开启、继续和新一轮均由家长端授权，停止不会清空进度。
document.querySelectorAll('[data-practice-action]').forEach(button=>button.addEventListener('click',async()=>{
  if(practicePosting)return;
  const action=button.dataset.practiceAction;practicePosting=true;renderPracticeControls();
  try{render(await api('/api/practice/action',{action}));toast(action==='start'?'已安排儿童端刷题':'已停止刷题，进度已保留')}
  catch(error){toast(error.message)}
  finally{practicePosting=false;renderPracticeControls()}
}));
// 点击名单按钮即可切换名单并展开对应的应用选择入口。
document.querySelectorAll('[data-list]').forEach(button=>button.addEventListener('click',()=>{
  const open=activeList!==button.dataset.list||$('addAppPanel').hidden;
  activeList=button.dataset.list;$('addAppPanel').hidden=!open;renderAppRules();
  if(open)$('appPicker').focus();
}));
// 外部点击只收起选择区域，保留点击位置本来的操作。
function closeAppSelection(){
  $('addAppPanel').hidden=true;$('appPicker').blur();
  document.querySelectorAll('[data-list]').forEach(button=>button.setAttribute('aria-expanded','false'));
}
document.addEventListener('click',event=>{
  if($('addAppPanel').hidden||$('addAppPanel').contains(event.target)||event.target.closest('[data-list]'))return;
  closeAppSelection();
});
document.addEventListener('keydown',event=>{if(event.key==='Escape')closeAppSelection()});
$('confirmAddApp').addEventListener('click',addAppToList);
$('refreshAppRules').addEventListener('click',loadAppRules);
$('refreshUsage').addEventListener('click',usage);
$('bankGrade').value=localStorage.getItem('watchgate_bank_grade')||'6';
$('bankSubject').value=localStorage.getItem('watchgate_bank_subject')||'数学';
$('bankTerm').value=localStorage.getItem('watchgate_bank_term')||'上';
$('bankDifficulty').value=localStorage.getItem('watchgate_bank_difficulty')||'standard';
$('bankGuidance').value=localStorage.getItem('watchgate_bank_guidance')||'';
$('bankGrade').addEventListener('change',()=>{localStorage.setItem('watchgate_bank_grade',$('bankGrade').value);renderChapters()});
$('bankSubject').addEventListener('change',()=>{localStorage.setItem('watchgate_bank_subject',$('bankSubject').value);renderChapters()});
$('bankTerm').addEventListener('change',()=>{localStorage.setItem('watchgate_bank_term',$('bankTerm').value);renderChapters()});
$('bankChapter').addEventListener('change',()=>{localStorage.setItem('watchgate_bank_chapter',$('bankChapter').value);syncTextbookLabel();loadQuestions()});
$('bankDifficulty').addEventListener('change',()=>localStorage.setItem('watchgate_bank_difficulty',$('bankDifficulty').value));
$('bankGuidance').addEventListener('input',()=>localStorage.setItem('watchgate_bank_guidance',$('bankGuidance').value));
let textbookEntries=[],textbookFileSequence=0,textbookSaving=false;
for(let grade=1;grade<=12;grade++)$('textbookImportGrade').add(new Option(gradeNames[grade],String(grade)));
function textbookImportError(message){$('textbookImportError').textContent=message;$('textbookImportError').hidden=!message}
function updateTextbookSave(){ $('saveTextbookImport').disabled=textbookSaving||!textbookEntries.some(item=>item.checked.checked); }
$('openTextbookImport').addEventListener('click',()=>{
  textbookFileSequence++;textbookEntries=[];$('textbookImportFiles').value='';$('textbookImportPreview').replaceChildren();textbookImportError('');
  $('textbookImportGrade').value=$('bankGrade').value||'6';$('textbookImportSubject').value=$('bankSubject').value||'数学';
  $('textbookImportTerm').value=$('bankTerm').value||'上';updateTextbookSave();$('textbookImportDialog').showModal();
});
$('closeTextbookImport').addEventListener('click',()=>{if(!textbookSaving){textbookFileSequence++;$('textbookImportDialog').close()}});
$('textbookImportDialog').addEventListener('cancel',event=>{if(textbookSaving)event.preventDefault();else textbookFileSequence++});
$('textbookImportFiles').addEventListener('change',async()=>{
  const sequence=++textbookFileSequence,files=[...$('textbookImportFiles').files];textbookEntries=[];
  $('textbookImportPreview').replaceChildren();textbookImportError('');updateTextbookSave();
  if(!files.length)return;
  try{
    if(files.length>30||files.some(file=>!/\.md$/i.test(file.name)))throw Error('请选择不超过 30 个 .md 文件');
    if(files.reduce((total,file)=>total+file.size,0)>6*1024*1024)throw Error('文件总大小超过 6 MB，请分批导入');
    const loaded=await Promise.all(files.map(async file=>{
      const text=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer()).replace(/^\uFEFF/,'');
      if(!text.trim()||text.length>250000)throw Error(file.name+' 为空或超过 25 万字符');
      const heading=text.match(/^#\s+(.+?)\s*$/m);
      return {name:file.name,markdown:text,title:heading?heading[1].trim():file.name.replace(/\.md$/i,'')};
    }));
    if(sequence!==textbookFileSequence)return;
    if(loaded.reduce((total,item)=>total+item.markdown.length,0)>1500000)throw Error('文件内容过大，请分批导入');
    for(const item of loaded){
      const row=document.createElement('div'),check=document.createElement('input'),details=document.createElement('div'),name=document.createElement('small'),title=document.createElement('input');
      row.className='textbook-preview-row';check.type='checkbox';check.checked=!(/^(00[_\s-]|readme)|总索引|原文阅读索引/i.test(item.name));
      check.setAttribute('aria-label','导入 '+item.name);title.type='text';title.value=item.title;title.maxLength=100;title.setAttribute('aria-label',item.name+' 的章节名称');
      name.textContent=item.name;details.append(name,title);row.append(check,details);$('textbookImportPreview').append(row);
      textbookEntries.push({markdown:item.markdown,checked:check,title});check.addEventListener('change',updateTextbookSave);
    }
    updateTextbookSave();
  }catch(error){if(sequence===textbookFileSequence)textbookImportError(error instanceof TypeError?'文件不是有效的 UTF-8 文本，请先转为 UTF-8 编码':error.message)}
});
$('saveTextbookImport').addEventListener('click',async()=>{
  if(textbookSaving)return;
  const entries=textbookEntries.filter(item=>item.checked.checked).map(item=>({title:item.title.value.trim(),markdown:item.markdown}));
  if(!entries.length||entries.some(item=>!item.title)){textbookImportError('请选择文件并填写章节名称');return}
  const grade=Number($('textbookImportGrade').value),subject=$('textbookImportSubject').value,term=$('textbookImportTerm').value;
  textbookSaving=true;updateTextbookSave();$('closeTextbookImport').disabled=true;textbookImportError('');
  try{
    const result=await api('/api/textbook/import',{grade,subject,term,chapters:entries});
    $('textbookImportDialog').close();toast('已保存 '+result.count+' 个教材章节');
    localStorage.setItem('watchgate_bank_grade',String(grade));localStorage.setItem('watchgate_bank_subject',subject);localStorage.setItem('watchgate_bank_term',term);
    try{
      curriculumBooks=await api('/api/curriculum');const book=curriculumBooks.find(item=>item.grade===grade&&item.subject===subject&&item.term===term);
      const chapter=book&&book.chapters.find(item=>item.title===entries[0].title);
      if(chapter)localStorage.setItem('watchgate_bank_chapter',chapter.id);
      renderChapters();
    }catch(error){toast('教材已保存，请刷新网页查看章节')}
  }catch(error){textbookImportError(error.message)}
  finally{textbookSaving=false;$('closeTextbookImport').disabled=false;updateTextbookSave()}
});
// 图片与文档互斥选择，由同一个生成按钮处理当前文件；清除文件后按章节生成。
$('examImages').addEventListener('change',()=>{if($('examImages').files.length)$('documentFile').value='';examLocalError='';syncGenerateButton()});
$('documentFile').addEventListener('change',()=>{if($('documentFile').files.length)$('examImages').value='';syncGenerateButton()});
$('clearExamImages').addEventListener('click',event=>{event.preventDefault();$('examImages').value='';$('documentFile').value='';examLocalError='';syncGenerateButton()});
$('generateBank').addEventListener('click',async()=>{
  const files=[...$('examImages').files];
  if(files.length){await analyzeExam(files);return}
  if($('documentFile').files.length){await analyzeDocument();return}
  const grade=Number($('bankGrade').value),term=$('bankTerm').value,chapterId=$('bankChapter').value,difficulty=$('bankDifficulty').value,guidance=$('bankGuidance').value.trim();
  if(!chapterId){toast('请选择教材章节');return}
  const details=guidance?'并参考你填写的出题说明':'';
  const subject=$('bankSubject').value;
  if(!confirm('将为“'+gradeNames[grade]+subject+term+'册 · '+$('bankChapter').selectedOptions[0].text+'”补齐 10 道 AI 基础题'+details+'。完成基础题后按“'+difficultyNames[difficulty]+'”难度自动补充拓展题；调用千问会按百炼用量计费。现在开始吗？'))return;
  latestJob='generate';latestJobStatus='正在提交生成任务';bankGenerating=true;generationSnapshot=null;syncGenerateButton();
  try{await api('/api/generate',{grade,term,chapterId,difficulty,guidance});await questionBank();toast('已开始生成，完成后题目会保存在儿童手机')}
  catch(error){bankGenerating=false;latestJobStatus='生成未开始：'+error.message;syncGenerateButton();toast(error.message)}
});
document.querySelectorAll('[data-question-filter]').forEach(button=>button.addEventListener('click',()=>{
  questionFilter=button.dataset.questionFilter;document.querySelectorAll('[data-question-filter]').forEach(item=>item.classList.toggle('selected',item===button));renderQuestionRows();
}));
document.querySelectorAll('[data-question-source]').forEach(button=>{
  button.classList.toggle('selected',button.dataset.questionSource===questionSource);
  button.addEventListener('click',()=>{
    questionSource=button.dataset.questionSource;localStorage.setItem('watchgate_question_source',questionSource);
    document.querySelectorAll('[data-question-source]').forEach(item=>item.classList.toggle('selected',item===button));
    questionData=null;$('questionRows').replaceChildren();$('questionSummary').textContent='正在读取'+sourceNames[questionSource]+'题库…';$('applyQuestionBank').disabled=true;$('deleteQuestionBank').disabled=true;loadQuestions();
  });
});
$('applyQuestionBank').addEventListener('click',async()=>{
  const rows=visibleQuestions();if(!rows.length||applyingBank)return;
  const source=questionSource;applyingBank=true;$('applyQuestionBank').disabled=true;
  try{
    await api('/api/question-bank/apply',{sourceType:source,grade:Number($('bankGrade').value),term:$('bankTerm').value,chapterId:source==='chapter'?$('bankChapter').value:'__all__',questionIds:rows.map(row=>row.id)});
    toast('已应用'+sourceNames[source]+'题库，儿童端下一轮开始使用');
  }catch(error){toast(error.message)}
  finally{applyingBank=false;$('applyQuestionBank').disabled=!visibleQuestions().length}
});
$('deleteQuestionBank').addEventListener('click',async()=>{
  if(!questionData||applyingBank||deletingBank)return;
  const rootIds=[...new Set(questionData.rows.map(row=>row.rootId))];
  if(!rootIds.length)return;
  const grade=Number($('bankGrade').value),term=$('bankTerm').value,source=questionSource;
  const chapterId=source==='chapter'?$('bankChapter').value:'__all__';
  const scope=source==='chapter'?'“'+$('bankChapter').selectedOptions[0].text+'”的章节题库':'所有年级和章节的'+sourceNames[source]+'题库';
  if(!confirm('删除'+scope+'及其变体？\n删除整个当前题库，不受筛选条件影响。若已暂停的刷题进度使用此题库，该轮进度会清除；历史成绩保留。'))return;
  if(source!==questionSource||(source==='chapter'&&(grade!==Number($('bankGrade').value)||term!==$('bankTerm').value||chapterId!==$('bankChapter').value)))return;
  deletingBank=true;$('deleteQuestionBank').disabled=true;$('applyQuestionBank').disabled=true;
  try{
    const result=await api('/api/question-bank/delete',{grade,term,chapterId,sourceType:source,rootIds});
    toast(result.clearedPractice?'题库已删除，相关刷题进度已清除':'当前题库已删除');
    questionData=null;await loadQuestions();await questionBank();
  }catch(error){toast(error.message)}
  finally{deletingBank=false;renderQuestionRows()}
});
async function analyzeExam(files){
  if(files.length<1||files.length>4){toast('请选择 1 至 4 张试卷照片');return}
  if(!confirm('图片将发送给百炼识别并核对；可用题会自动导入所选章节，调用模型按用量计费。继续吗？'))return;
  latestJob='exam';latestJobStatus='正在处理并上传图片';examLocalError='';examUploading=true;syncGenerateButton();$('examStatus').textContent='正在处理并上传图片…';$('examStatus').hidden=false;
  try{
    const images=[];for(const file of files)images.push(await readImage(file));
    if(images.reduce((sum,image)=>sum+image.length,0)>11*1024*1024)throw Error('图片太大，请减少张数或使用较小的图片');
    await api('/api/exam/analyze',{grade:Number($('bankGrade').value),term:$('bankTerm').value,chapterId:$('bankChapter').value,images});examRunning=true;$('examImages').value='';toast('已开始分析试卷');
  }catch(error){examLocalError=error.message;latestJobStatus='图片处理失败：'+error.message;$('examStatus').textContent=error.message;toast(error.message)}
  finally{examUploading=false;syncGenerateButton();examState()}
}
function reviewQuestion(){
  return {subject:$('reviewSubject').value.trim(),grade:Number($('reviewGrade').value),stem:$('reviewStem').value.trim(),
    chapterId:$('reviewChapter').value,
    options:[0,1,2,3].map(index=>$('reviewOption'+index).value.trim()),
    answerIndex:$('reviewAnswer').value===''?-1:Number($('reviewAnswer').value),explanation:$('reviewExplanation').value.trim()};
}
function syncReviewAiState(){
  const issue=reviewQueue.find(item=>item.id===activeReviewId),running=!!issue&&issue.aiState==='running';
  const busy=reviewSubmitting||running;
  $('confirmReview').disabled=busy;$('skipReview').disabled=busy;$('aiCompleteReview').disabled=busy;
  $('closeReview').disabled=reviewSubmitting;
  $('reviewForm').querySelectorAll('input,textarea,select').forEach(field=>field.disabled=busy);
  $('aiCompleteReview').textContent=running?'AI补全中…':'AI补全并加入题库';
  $('reviewAiStatus').hidden=!issue||!issue.aiMessage;
  $('reviewAiStatus').textContent=issue&&issue.aiMessage||'';
  $('reviewAiStatus').classList.toggle('failed',!!issue&&issue.aiState==='failed');
}
function fillReviewChapters(preferred){
  const select=$('reviewChapter'),grade=Number($('reviewGrade').value);select.replaceChildren(new Option('请选择所属章节',''));
  for(const book of curriculumBooks)if(book.grade===grade)for(const chapter of book.chapters)select.add(new Option(book.subject+' · '+book.term+'册 · '+chapter.title,chapter.id));
  if([...select.options].some(option=>option.value===preferred))select.value=preferred;
}
function openImportReview(id,useServerDraft=false){
  if(reviewSubmitting)return;
  const issue=reviewQueue.find(item=>item.id===id);if(!issue)return;
  let draft=issue.draft||{};
  if(useServerDraft)sessionStorage.removeItem('watchgate_review_draft_'+id);
  else try{draft=JSON.parse(sessionStorage.getItem('watchgate_review_draft_'+id))||draft}catch(error){}
  activeReviewId=id;
  $('reviewTitle').textContent=(sourceNames[issue.sourceType]||'导入')+' · 第 '+issue.number+' 题';
  $('reviewReason').textContent=issue.reason||'请核对题目与正确答案';
  $('reviewSubject').value=draft.subject||'';
  $('reviewGrade').value=String(draft.grade||Number($('bankGrade').value));
  fillReviewChapters(draft.chapterId||issue.chapterId||'');
  $('reviewStem').value=draft.stem||issue.stem||'';
  const options=Array.isArray(draft.options)?draft.options:[];
  for(let i=0;i<4;i++)$('reviewOption'+i).value=typeof options[i]==='string'?options[i]:'';
  $('reviewAnswer').value=Number.isInteger(draft.answerIndex)&&draft.answerIndex>=0&&draft.answerIndex<4?String(draft.answerIndex):'';
  $('reviewExplanation').value=draft.explanation||(Array.isArray(draft.hints)?draft.hints.filter(item=>typeof item==='string').join('\n'):'');
  $('reviewError').hidden=true;
  syncReviewAiState();
  if(!$('importReviewDialog').open)$('importReviewDialog').showModal();
}
function showNextImportReview(){
  if(activeReviewId||reviewSubmitting||reviewAutoPaused||examRunning||documentRunning||examUploading||documentUploading||!key)return;
  if(reviewQueue.length)openImportReview(reviewQueue[0].id);
}
async function loadImportReviews(){
  if(!key||reviewLoading||reviewSubmitting)return;
  reviewLoading=true;
  try{
    const previous=reviewQueue.find(item=>item.id===activeReviewId);
    const data=await api('/api/import-reviews');reviewQueue=data.questions||[];
    if(activeReviewId&&!reviewQueue.some(item=>item.id===activeReviewId)){
      sessionStorage.removeItem('watchgate_review_draft_'+activeReviewId);
      $('importReviewDialog').close();activeReviewId='';
      if(previous&&previous.aiState==='running'){toast('AI补全并核对完成，题目已加入题库');await Promise.all([loadQuestions(),examState(),documentState()])}
    }
    const current=reviewQueue.find(item=>item.id===activeReviewId);
    if(current&&previous&&previous.aiState==='running'&&current.aiState==='failed')openImportReview(current.id,true);
    syncReviewAiState();
    const fingerprint=JSON.stringify(reviewQueue);
    if(fingerprint!==reviewFingerprint){
      reviewFingerprint=fingerprint;const container=$('importReviews');container.replaceChildren();container.hidden=!reviewQueue.length;
      for(const issue of reviewQueue){
        const row=document.createElement('div');row.className='review-item';
        const text=document.createElement('p');text.textContent=(sourceNames[issue.sourceType]||'导入')+'第 '+issue.number+' 题：'+issue.stem+'\n'+issue.reason;
        const button=document.createElement('button');button.type='button';button.textContent=issue.aiState==='running'?'查看补全进度':'修改并确认';button.addEventListener('click',()=>openImportReview(issue.id));
        row.append(text,button);container.append(row);
      }
    }
    showNextImportReview();
  }catch(error){/* 断线后由下一次轮询继续读取，保留正在编辑的内容。 */}
  finally{reviewLoading=false}
}
async function resolveImportReview(action){
  if(!activeReviewId||reviewSubmitting)return;
  const id=activeReviewId,question=reviewQuestion();
  if(action==='add'&&(!question.chapterId||!question.subject||!question.stem||question.options.some(item=>!item)||new Set(question.options).size!==4||question.answerIndex<0)){
    $('reviewError').textContent='请选择所属章节，并补全题干、四个不同的选项及正确答案';$('reviewError').hidden=false;return;
  }
  reviewSubmitting=true;syncReviewAiState();
  try{
    const result=await api('/api/import-reviews/resolve',{id,action,question});
    sessionStorage.removeItem('watchgate_review_draft_'+id);reviewQueue=reviewQueue.filter(item=>item.id!==id);
    activeReviewId='';$('importReviewDialog').close();
    toast(result.alreadyHandled?'该题已处理':action==='skip'?'该题不加入题库':result.added?'已加入题库':'题库已有相同题目，无需重复加入');
    await Promise.all([loadQuestions(),examState(),documentState()]);
  }catch(error){$('reviewError').textContent=error.message;$('reviewError').hidden=false}
  finally{
    reviewSubmitting=false;syncReviewAiState();
    await loadImportReviews();
  }
}
async function completeImportReview(){
  if(!activeReviewId||reviewSubmitting)return;
  const id=activeReviewId,question=reviewQuestion();
  if(!question.stem||!question.chapterId){$('reviewError').textContent='请先选择所属章节并补充原题题干';$('reviewError').hidden=false;return}
  reviewSubmitting=true;syncReviewAiState();$('reviewError').hidden=true;
  try{
    const result=await api('/api/import-reviews/ai',{id,question});
    if(!result.alreadyHandled){
      const issue=reviewQueue.find(item=>item.id===id);
      if(issue){issue.aiState='running';issue.aiMessage='正在AI补全题目…'}
      sessionStorage.removeItem('watchgate_review_draft_'+id);
    }
  }catch(error){$('reviewError').textContent=error.message;$('reviewError').hidden=false}
  finally{reviewSubmitting=false;syncReviewAiState();await loadImportReviews()}
}
// 关闭仅表示稍后处理，不会把题目判定为“不加入”。未提交的编辑在刷新后保留。
function deferImportReview(){reviewAutoPaused=true;activeReviewId='';$('importReviewDialog').close()}
for(const [value,label] of Object.entries(gradeNames))$('reviewGrade').append(new Option(label,value));
$('reviewGrade').addEventListener('change',()=>fillReviewChapters(''));
$('reviewForm').addEventListener('input',()=>{if(activeReviewId)sessionStorage.setItem('watchgate_review_draft_'+activeReviewId,JSON.stringify(reviewQuestion()))});
$('reviewForm').addEventListener('submit',event=>{event.preventDefault();resolveImportReview('add')});
$('skipReview').addEventListener('click',()=>resolveImportReview('skip'));
$('aiCompleteReview').addEventListener('click',completeImportReview);
$('closeReview').addEventListener('click',deferImportReview);
$('importReviewDialog').addEventListener('cancel',event=>{event.preventDefault();if(!reviewSubmitting)deferImportReview()});

function readDocument(file){
  return new Promise((resolve,reject)=>{
    const reader=new FileReader();reader.onerror=()=>reject(Error('文件读取失败'));
    reader.onload=()=>{const data=String(reader.result),comma=data.indexOf(',');if(comma<0)reject(Error('文件读取失败'));else resolve(data.slice(comma+1))};
    reader.readAsDataURL(file);
  });
}
async function documentState(){
  if(!key)return;
  try{
    const data=await api('/api/document');documentRunning=!!data.running;
    if(data.running)latestJob='document';if(latestJob==='document')latestJobStatus=data.status||'正在处理文档';syncGenerateButton();
  }catch(error){if(!documentUploading&&latestJob==='document'){latestJobStatus='连接中断，暂时无法读取文档进度';syncGenerateButton()}}
}
async function analyzeDocument(){
  const file=$('documentFile').files[0],chapterId=$('bankChapter').value;
  if(!file){toast('请选择文档');return}
  if(!/\.docx?$/i.test(file.name)){toast('请选择 .doc 或 .docx 文件');return}
  if(file.size>4*1024*1024||file.size===0){toast('请选择不超过 4 MB 的非空文件');return}
  if(!chapterId){toast('请先选择要导入的章节');return}
  if(!confirm('文档将发送到阿里云百炼解析并核对；可用题会自动导入所选章节，模型调用按用量计费。继续吗？'))return;
  latestJob='document';latestJobStatus='正在读取并上传文档';documentUploading=true;
  syncGenerateButton();
  try{
    const content=await readDocument(file);
    await api('/api/document/prepare',{filename:file.name,content,grade:Number($('bankGrade').value),term:$('bankTerm').value,chapterId});
    documentRunning=true;$('documentFile').value='';latestJobStatus='正在解析文档…';
  }catch(error){latestJobStatus='文档处理失败：'+error.message;toast(error.message)}
  finally{documentUploading=false;syncGenerateButton();if(documentRunning)documentState()}
}
$('retryExam').addEventListener('click',async event=>{
  event.preventDefault();if(examRunning||bankGenerating||examUploading)return;
  if(!confirm('继续调用千问生成尚未完成的专项题，百炼会按用量计费。继续吗？'))return;
  try{await api('/api/exam/retry',{});examRunning=true;syncGenerateButton();examState()}catch(error){toast(error.message)}
});
$('logout').addEventListener('click',()=>{key='';localStorage.removeItem('watchgate_key');sessionStorage.removeItem('watchgate_key');showLogin()});
$('familyShareCreate').addEventListener('click',async()=>{
  try{const data=await api('/api/family-link',{});renderFamilyShare(data.token);toast('分享链接已生成')}
  catch(error){toast(error.message)}
});
$('familyShareCopy').addEventListener('click',copyFamilyShare);
$('familyShareRevoke').addEventListener('click',async()=>{
  if(!familyShareToken||!confirm('撤销分享链接？已分享给家人的链接将立即失效。'))return;
  try{await api('/api/family-link/revoke',{});renderFamilyShare('');toast('分享链接已撤销')}
  catch(error){toast(error.message)}
});

if(key){refresh();usage();loadAppRules();loadCurriculum();loadSchedules();loadFamilyShare();syncGeneration()}
else showLogin();

// 空闲时减少查询；后台有任务时保持较快的进度更新。
function scheduleGeneration(delay){
  clearTimeout(generationTimer);
  generationTimer=setTimeout(syncGeneration,delay);
}
async function syncGeneration(){
  clearTimeout(generationTimer);generationTimer=null;
  if(!key||generationPolling||document.hidden)return;
  generationPolling=true;
  try {
    const wasBusy=bankGenerating||examRunning||documentRunning||!!(generationSnapshot&&generationSnapshot.autoSupplyRunning);
    const previousCount=generationSnapshot?generationSnapshot.questions:null;
    const previousSupplyRevision=generationSnapshot?generationSnapshot.autoSupplyRevision:null;
    await Promise.all([questionBank(),examState(),documentState()]);
    const busy=bankGenerating||examRunning||documentRunning||!!(generationSnapshot&&generationSnapshot.autoSupplyRunning);
    await loadImportReviews();
    if((wasBusy&&!busy)||(generationSnapshot&&previousCount!==generationSnapshot.questions)||
        (generationSnapshot&&previousSupplyRevision!==null&&previousSupplyRevision!==generationSnapshot.autoSupplyRevision))await loadQuestions();
  } finally {
    generationPolling=false;
    if(key&&!document.hidden){
      const busy=bankGenerating||!!(generationSnapshot&&generationSnapshot.autoSupplyRunning)||examRunning||documentRunning||examUploading||documentUploading||reviewQueue.some(item=>item.aiState==='running');
      scheduleGeneration(busy?2500:10000);
    }
  }
}
setInterval(()=>{if(!document.hidden)refresh()},5000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden){refresh();syncGeneration()}});
window.addEventListener('focus',()=>{refresh();syncGeneration()});
