#!/usr/bin/env bash
set -euo pipefail

# Render captured app screens into the README gallery, tour, and sharing card.
capture_dir=${1:?Usage: bash scripts/build-demo.sh /path/to/ui-checks}
project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output_dir="$project_dir/docs/media"
mkdir -p "$output_dir"

ffmpeg -hide_banner -loglevel error -y -i "$capture_dir/remote.png" \
  -vf scale=540:-2 -frames:v 1 "$output_dir/remote.png"
ffmpeg -hide_banner -loglevel error -y -i "$capture_dir/library-movies.png" \
  -vf scale=540:-2 -frames:v 1 "$output_dir/library.png"
ffmpeg -hide_banner -loglevel error -y -i "$capture_dir/library-episode.png" \
  -vf scale=540:-2 -frames:v 1 "$output_dir/episode.png"

tour_inputs=()
for screen in remote library-movies library-series library-seasons library-episodes library-episode; do
  tour_inputs+=(-loop 1 -framerate 12 -t 3 -i "$capture_dir/$screen.png")
done
ffmpeg -hide_banner -loglevel error -y "${tour_inputs[@]}" -filter_complex_threads 1 \
  -filter_complex '
    [0:v]scale=432:-2,setsar=1,settb=AVTB,format=rgba[s0];
    [1:v]scale=432:-2,setsar=1,settb=AVTB,format=rgba[s1];
    [2:v]scale=432:-2,setsar=1,settb=AVTB,format=rgba[s2];
    [3:v]scale=432:-2,setsar=1,settb=AVTB,format=rgba[s3];
    [4:v]scale=432:-2,setsar=1,settb=AVTB,format=rgba[s4];
    [5:v]scale=432:-2,setsar=1,settb=AVTB,format=rgba[s5];
    [s0][s1]xfade=transition=fade:duration=0.3:offset=2.7[t1];
    [t1][s2]xfade=transition=fade:duration=0.3:offset=5.4[t2];
    [t2][s3]xfade=transition=fade:duration=0.3:offset=8.1[t3];
    [t3][s4]xfade=transition=fade:duration=0.3:offset=10.8[t4];
    [t4][s5]xfade=transition=fade:duration=0.3:offset=13.5,split[colors][frames];
    [colors]palettegen=max_colors=128:stats_mode=diff[palette];
    [frames][palette]paletteuse=dither=bayer:bayer_scale=4[out]
  ' -map '[out]' -loop 0 "$output_dir/demo.gif"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i color=c=0x09121f:s=1280x720:d=1 \
  -i "$output_dir/remote.png" -i "$output_dir/library.png" -i "$output_dir/episode.png" \
  -filter_complex_threads 1 -filter_complex '
    [1:v]scale=220:-2[p1];[2:v]scale=220:-2[p2];[3:v]scale=220:-2[p3];
    [0:v]drawbox=x=0:y=0:w=1280:h=6:color=0x79dceb:t=fill,
      drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text=JellyRemote:fontsize=58:fontcolor=white:x=62:y=128,
      drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text=Choose your next watch.:fontsize=23:fontcolor=0xd9e6ed:x=64:y=232,
      drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text=Control it from your phone.:fontsize=23:fontcolor=0xd9e6ed:x=64:y=271,
      drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text=ANDROID 8.0+  /  FREE APK:fontsize=15:fontcolor=0x79dceb:x=64:y=365,
      drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text=Remote control for Jellyfin:fontsize=19:fontcolor=0xa7bbcd:x=64:y=408,
      drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text=github.com/TeeWare2026/JellyRemote:fontsize=17:fontcolor=0xa7bbcd:x=64:y=606[base];
    [base][p1]overlay=x=500:y=160[v1];
    [v1][p2]overlay=x=750:y=160[v2];
    [v2][p3]overlay=x=1000:y=160,
      drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text=Demo library shown:fontsize=13:fontcolor=0xa7bbcd:x=500:y=610[out]
  ' -map '[out]' -frames:v 1 "$output_dir/share-card.png"
