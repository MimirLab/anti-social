import json,os,resource,subprocess,sys,time
from pathlib import Path
prefix=Path(sys.argv[1]);start=time.monotonic();load_start=os.getloadavg()
root=Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
env=os.environ.copy()
env.update({'TMPDIR':str(root/'java-tmp'),'XDG_CACHE_HOME':str(root/'inference-kv/cache'),'MESA_SHADER_CACHE_DIR':str(root/'inference-kv/mesa-cache'),'__GL_SHADER_DISK_CACHE_PATH':str(root/'inference-kv/nvidia-cache')})
for key in ['TMPDIR','XDG_CACHE_HOME','MESA_SHADER_CACHE_DIR','__GL_SHADER_DISK_CACHE_PATH']:Path(env[key]).mkdir(parents=True,exist_ok=True)
process_tree=env.get('ANTISOCIAL_MEASURE_PROCESS_TREE')=='true'
sampled={'samples':0,'max_pss_kib':0,'max_drm_resident_kib':0,'max_pss_plus_drm_resident_kib':0,'drm_drivers':[],'max_processes':0}
def descendants(pid):
    pending=[pid];seen=set()
    while pending:
        current=pending.pop()
        if current in seen:continue
        seen.add(current)
        for children in Path(f'/proc/{current}/task').glob('*/children'):
            try:pending.extend(int(child) for child in children.read_text().split())
            except (OSError,ValueError):pass
    return seen
def observation(pid):
    try:
        pss=next(int(line.split()[1]) for line in Path(f'/proc/{pid}/smaps_rollup').read_text().splitlines() if line.startswith('Pss:'))
    except (OSError,StopIteration,ValueError):return None
    clients={}
    for file in Path(f'/proc/{pid}/fdinfo').glob('*'):
        try:
            info=dict(line.split(':',1) for line in file.read_text().splitlines() if ':' in line)
            if 'drm-client-id' not in info:continue
            key=(info.get('drm-pdev','').strip(),info['drm-client-id'].strip())
            resident=0
            for name,value in info.items():
                if name.startswith('drm-resident-'):
                    parts=value.split();resident+=int(parts[0])*(1024 if len(parts)>1 and parts[1]=='MiB' else 1)
            clients[key]=max(clients.get(key,0),resident)
            driver=info.get('drm-driver','').strip()
            if driver and driver not in sampled['drm_drivers']:sampled['drm_drivers'].append(driver)
        except (OSError,ValueError):continue
    return pss,clients
def sample(pid):
    clients={};pss=0;processes=0
    for child in descendants(pid) if process_tree else [pid]:
        current=observation(child)
        if current is None:continue
        processes+=1;pss+=current[0]
        for key,value in current[1].items():clients[key]=max(clients.get(key,0),value)
    if not processes:return
    drm=sum(clients.values());sampled['samples']+=1
    sampled['max_processes']=max(sampled['max_processes'],processes)
    sampled['max_pss_kib']=max(sampled['max_pss_kib'],pss)
    sampled['max_drm_resident_kib']=max(sampled['max_drm_resident_kib'],drm)
    sampled['max_pss_plus_drm_resident_kib']=max(sampled['max_pss_plus_drm_resident_kib'],pss+drm)
with prefix.with_suffix('.stdout').open('w') as out,prefix.with_suffix('.stderr').open('w') as err:
    result=subprocess.Popen(sys.argv[2:],stdout=out,stderr=err,env=env)
    while result.poll() is None:
        sample(result.pid)
        try:result.wait(timeout=0.5)
        except subprocess.TimeoutExpired:pass
usage=resource.getrusage(resource.RUSAGE_CHILDREN)
receipt={'command':sys.argv[2:],'returncode':result.returncode,'elapsed_seconds':time.monotonic()-start,'max_rss_kib':usage.ru_maxrss,'user_seconds':usage.ru_utime,'system_seconds':usage.ru_stime,'load_average_start':load_start,'load_average_end':os.getloadavg(),'cpu_count':os.cpu_count(),'cache_directories':{key:env[key] for key in ['XDG_CACHE_HOME','MESA_SHADER_CACHE_DIR','__GL_SHADER_DISK_CACHE_PATH']},'limit':'child-process RSS excludes GPU/driver allocations; concurrent engineering work can affect elapsed times'}
receipt['sampled_memory']=sampled
receipt['process_tree']=process_tree
receipt['sampled_memory_limit']='500 ms '+('process-tree' if process_tree else 'single-process')+' PSS and deduplicated DRM per-client resident observations; short-lived processes/peaks may be missed. UMA mappings may overlap, so their sum is an upper accounting estimate, not unique physical bytes. Driver-global and other apps excluded. No complete phone/browser-plus-AI or energy claim.'
prefix.with_suffix('.measurement.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt));sys.exit(result.returncode)
