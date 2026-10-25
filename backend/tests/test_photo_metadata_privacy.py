"""Privacy check: location and camera details inside a photo never reach the AI provider."""
import io

from PIL import Image

from server.providers import image_payload


def photo_with_location():
    image = Image.new('RGB', (64, 48), (180, 90, 90))
    exif = Image.Exif()
    exif[0x010F] = 'Samsung'           # camera maker
    exif[0x0110] = 'SM-S928W'          # camera model
    exif[0x0132] = '2026:10:24 09:15:00'  # date/time
    exif[0x0112] = 6                   # orientation: rotated
    gps = exif.get_ifd(0x8825)
    gps[1] = 'N'; gps[2] = (43.0, 39.0, 1.0); gps[3] = 'W'; gps[4] = (79.0, 23.0, 2.0)
    out = io.BytesIO()
    image.save(out, format='JPEG', exif=exif)
    return out.getvalue()


def test_photo_sent_to_ai_has_no_location_camera_or_time_details():
    original = photo_with_location()
    with Image.open(io.BytesIO(original)) as source:
        assert source.getexif().get_ifd(0x8825)  # the test photo really carries GPS
    sent = image_payload(original)
    with Image.open(io.BytesIO(sent)) as result:
        assert not result.getexif(), 'no EXIF block at all'
        assert 'exif' not in result.info and 'xmp' not in result.info
        assert result.size == (48, 64), 'orientation is applied to the pixels before the tag is dropped'
    assert b'SM-S928W' not in sent and b'Samsung' not in sent and b'2026:10:24' not in sent
