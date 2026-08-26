import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../core/api_client.dart';
import '../theme/app_theme.dart';

enum RecordType { song, stable }

class RecordsScreen extends StatefulWidget {
  const RecordsScreen({super.key, required this.api, required this.type});
  final WorkstationApi api;
  final RecordType type;

  @override
  State<RecordsScreen> createState() => _RecordsScreenState();
}

class _RecordsScreenState extends State<RecordsScreen> {
  final query = TextEditingController();
  Timer? debounce;
  bool loading = true;
  String? error;
  List<Map<String, dynamic>> items = const [];

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void dispose() {
    debounce?.cancel();
    query.dispose();
    super.dispose();
  }

  Future<void> load() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final result = widget.type == RecordType.song
          ? await widget.api.songs(query.text)
          : await widget.api.stable(query.text);
      if (mounted) setState(() => items = result);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  void search(String _) {
    debounce?.cancel();
    debounce = Timer(const Duration(milliseconds: 380), load);
  }

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(18, 6, 18, 12),
        child: TextField(
          controller: query,
          onChanged: search,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => load(),
          decoration: InputDecoration(
            hintText: widget.type == RecordType.song
                ? '搜索歌曲、作者、谱师或 ID'
                : '搜索 Stable 名称或 SID',
            prefixIcon: const Icon(Icons.search_rounded),
            suffixIcon: query.text.isEmpty
                ? null
                : IconButton(
                    onPressed: () {
                      query.clear();
                      load();
                    },
                    icon: const Icon(Icons.close_rounded),
                  ),
          ),
        ),
      ),
      if (loading) const LinearProgressIndicator(minHeight: 2),
      Expanded(
        child: error != null
            ? _Message(icon: Icons.cloud_off_rounded, text: error!, retry: load)
            : items.isEmpty
            ? const _Message(icon: Icons.search_off_rounded, text: '没有找到记录')
            : RefreshIndicator(
                onRefresh: load,
                child: ListView.separated(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(18, 4, 18, 28),
                  itemCount: items.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 9),
                  itemBuilder: (context, index) => _RecordCard(
                    item: items[index],
                    type: widget.type,
                    onTap: () => _edit(items[index]),
                  ),
                ),
              ),
      ),
    ],
  );

  Future<void> _edit(Map<String, dynamic> item) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: const Color(0xFFFFFBFE),
      showDragHandle: true,
      builder: (context) =>
          _RecordEditor(api: widget.api, type: widget.type, item: item),
    );
    if (saved == true) await load();
  }
}

class _RecordCard extends StatelessWidget {
  const _RecordCard({
    required this.item,
    required this.type,
    required this.onTap,
  });
  final Map<String, dynamic> item;
  final RecordType type;
  final VoidCallback onTap;

  String value(List<String> keys) {
    for (final key in keys) {
      final match = item.keys
          .where((candidate) => candidate.toLowerCase() == key.toLowerCase())
          .firstOrNull;
      if (match != null && item[match].toString().trim().isNotEmpty) {
        return item[match].toString();
      }
    }
    return '';
  }

  @override
  Widget build(BuildContext context) {
    final id = value(
      type == RecordType.song ? const ['id'] : const ['sid', 'id'],
    );
    final title = value(
      type == RecordType.song
          ? const ['song_name', 'name', 'title']
          : const ['song_name', 'name', 'title', 'song'],
    );
    final subtitle = type == RecordType.song
        ? [
            value(const ['author']),
            value(const ['charter']),
            if (value(const ['bpm']).isNotEmpty) 'BPM ${value(const ['bpm'])}',
            value(const ['duration']),
          ].where((v) => v.isNotEmpty).join(' · ')
        : item.entries
              .where(
                (entry) =>
                    !{'sid', 'id', 'cover'}.contains(entry.key.toLowerCase()) &&
                    entry.value.toString().trim().isNotEmpty,
              )
              .take(2)
              .map((entry) => entry.value)
              .join(' · ');
    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 7),
        leading: Container(
          width: 43,
          height: 43,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: const Color(0xFFF8EEF6),
            borderRadius: BorderRadius.circular(13),
          ),
          child: Text(
            id.isEmpty ? '—' : id,
            maxLines: 1,
            overflow: TextOverflow.fade,
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              color: AppTheme.accent,
            ),
          ),
        ),
        title: Text(
          title.isEmpty ? '未命名记录' : title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text(
          subtitle.isEmpty ? '点击查看字段' : subtitle,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        trailing: const Icon(Icons.edit_outlined, size: 19),
        onTap: onTap,
      ),
    );
  }
}

class _RecordEditor extends StatefulWidget {
  const _RecordEditor({
    required this.api,
    required this.type,
    required this.item,
  });
  final WorkstationApi api;
  final RecordType type;
  final Map<String, dynamic> item;

  @override
  State<_RecordEditor> createState() => _RecordEditorState();
}

class _RecordEditorState extends State<_RecordEditor> {
  late final Map<String, TextEditingController> fields;
  bool saving = false;

  @override
  void initState() {
    super.initState();
    const songOrder = [
      'song_name',
      'author',
      'charter',
      'bpm',
      'duration',
      'album',
      'album_ids',
      'album_date',
      'album_image_path',
      'song_nickname',
      'song_nickname2',
      'song_nickname3',
      'song_nickname4',
      'song_nickname5',
      'song_nickname6',
      'artist_nickname',
      'image_path',
      'audio_path',
    ];
    final ordered = <String, TextEditingController>{};
    if (widget.type == RecordType.song) {
      for (final expected in songOrder) {
        for (final entry in widget.item.entries) {
          if (entry.key.toLowerCase() == expected) {
            ordered[entry.key] = TextEditingController(
              text: entry.value?.toString() ?? '',
            );
            break;
          }
        }
      }
      for (final entry in widget.item.entries) {
        if (!ordered.keys.any(
          (key) => key.toLowerCase() == entry.key.toLowerCase(),
        )) {
          ordered[entry.key] = TextEditingController(
            text: entry.value?.toString() ?? '',
          );
        }
      }
    } else {
      for (final entry in widget.item.entries) {
        if (!{'sid', 'id', 'cover'}.contains(entry.key.toLowerCase())) {
          ordered[entry.key] = TextEditingController(
            text: entry.value?.toString() ?? '',
          );
        }
      }
    }
    fields = ordered;
  }

  @override
  void dispose() {
    for (final controller in fields.values) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> save() async {
    setState(() => saving = true);
    try {
      final values = fields.map(
        (key, controller) => MapEntry(key, controller.text),
      );
      if (widget.type == RecordType.song) {
        final id = widget.item.entries
            .firstWhere((entry) => entry.key.toLowerCase() == 'id')
            .value
            .toString();
        await widget.api.updateSong(id, values);
      } else {
        final sidEntry = widget.item.entries
            .where((entry) => entry.key.toLowerCase() == 'sid')
            .firstOrNull;
        if (sidEntry == null) throw const ApiException('这条 Stable 记录缺少 SID');
        await widget.api.updateStable(sidEntry.value.toString(), values);
      }
      if (mounted) Navigator.pop(context, true);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  Future<void> uploadAsset(String type) async {
    final idEntry = widget.item.entries
        .where((entry) => entry.key.toLowerCase() == 'id')
        .firstOrNull;
    if (idEntry == null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('这首歌曲缺少 ID')));
      return;
    }
    final image = type == 'image';
    final picked = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: image
          ? const ['jpg', 'jpeg', 'png', 'webp']
          : const ['mp3', 'wav', 'flac', 'm4a', 'ogg'],
      withData: false,
    );
    if (picked == null || picked.files.isEmpty) return;
    final file = picked.files.single;
    setState(() => saving = true);
    try {
      final bytes = file.path != null
          ? await File(file.path!).readAsBytes()
          : file.bytes;
      if (bytes == null || bytes.isEmpty) throw const ApiException('无法读取选择的文件');
      await widget.api.uploadSongAsset(
        id: idEntry.value.toString(),
        type: type,
        filename: file.name,
        bytes: bytes,
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(image ? '歌曲图片已压缩并发布' : '歌曲音频已压缩并发布')),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.fromLTRB(
      20,
      0,
      20,
      MediaQuery.viewInsetsOf(context).bottom + 20,
    ),
    child: Column(
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                widget.type == RecordType.song ? '编辑歌曲信息' : '编辑 Stable 记录',
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            IconButton(
              onPressed: () => Navigator.pop(context),
              icon: const Icon(Icons.close_rounded),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Expanded(
          child: ListView.separated(
            itemCount: fields.length,
            separatorBuilder: (_, _) => const SizedBox(height: 11),
            itemBuilder: (context, index) {
              final entry = fields.entries.elementAt(index);
              return TextField(
                controller: entry.value,
                readOnly: entry.key.toLowerCase() == 'id',
                minLines: 1,
                maxLines: entry.key.toLowerCase().contains('nickname') ? 3 : 1,
                decoration: InputDecoration(labelText: _fieldLabel(entry.key)),
              );
            },
          ),
        ),
        const SizedBox(height: 14),
        if (widget.type == RecordType.song) ...[
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: saving ? null : () => uploadAsset('image'),
                  icon: const Icon(Icons.image_outlined),
                  label: const Text('更换图片'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: saving ? null : () => uploadAsset('audio'),
                  icon: const Icon(Icons.audio_file_outlined),
                  label: const Text('更换音频'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
        ],
        SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            onPressed: saving ? null : save,
            icon: saving
                ? const SizedBox.square(
                    dimension: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.save_outlined),
            label: Text(saving ? '正在保存' : '保存修改'),
          ),
        ),
      ],
    ),
  );

  String _fieldLabel(String key) {
    const labels = {
      'song_name': '歌名',
      'author': '作者',
      'charter': '谱师',
      'bpm': 'BPM',
      'duration': '时长',
      'album': '专辑',
      'album_ids': '专辑 ID',
      'album_date': '收录日期',
      'album_image_path': '专辑图片路径',
      'song_nickname': '歌曲昵称 1',
      'song_nickname2': '歌曲昵称 2',
      'song_nickname3': '歌曲昵称 3',
      'song_nickname4': '歌曲昵称 4',
      'song_nickname5': '歌曲昵称 5',
      'song_nickname6': '歌曲昵称 6',
      'artist_nickname': '作者昵称',
      'image_path': '歌曲图片路径',
      'audio_path': '歌曲音频路径',
    };
    return labels[key.toLowerCase()] ?? key;
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.icon, required this.text, this.retry});
  final IconData icon;
  final String text;
  final VoidCallback? retry;
  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(30),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 36, color: AppTheme.muted),
          const SizedBox(height: 12),
          Text(
            text,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          if (retry != null) ...[
            const SizedBox(height: 14),
            OutlinedButton(onPressed: retry, child: const Text('重试')),
          ],
        ],
      ),
    ),
  );
}
