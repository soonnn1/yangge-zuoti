// 把当前儿童手机地址交给已安装的家长端。
document.getElementById('open-app').href='watchgateparent://connect?host='+encodeURIComponent(location.hostname);
